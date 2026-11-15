package city.core

import kotlin.math.*

internal fun Citizen.acceptsEducation(building: String?): Boolean =
    when {
        life?.stage == LifeStage.STUDENT -> building == "college"
        age in 3..17 -> building == "school"
        age >= 18 -> building in setOf("school", "college", "library", "museum")
        else -> false
    }

internal fun selectStudies(
    person: Citizen,
    city: City,
    campus: Int?,
): Citizen {
    val life = person.life ?: return person
    if (life.stage != LifeStage.APPLICANT) return person
    val eligible = Course.entries.filter { life.knowledge >= it.minimumKnowledge }
    val study =
        if (campus != null && eligible.isNotEmpty() && draw(city, person.id, 701) < LifeRules.UNIVERSITY_CHANCE) {
            Study(eligible[noise(city.seed xor person.id, 702, city.nextDay()) % eligible.size], campus, started = city.nextDay())
        } else {
            null
        }
    return person.copy(life = life.copy(stage = if (study == null) LifeStage.WORKER else LifeStage.STUDENT, study = study, lastSalary = if (study == null) 50.0 else 0.0), routes = emptyList(), wellbeing = person.wellbeing.copy(workplace = null))
}

/** Daily instruction uses executed stays, never the optimistic morning plan. */
internal fun finishEducationDay(city: City): City {
    val executions =
        city.mobility
            ?.homes
            ?.associateBy { it.cell }
            .orEmpty()
    return city.copy(
        households =
            city.households.map { home ->
                val days = executions[home.cell]?.days?.associateBy { it.citizenId }.orEmpty()
                home.copy(people = home.people.map { person -> advanceEducation(person, days[person.id], city) })
            },
    )
}

internal fun advanceEducation(
    person: Citizen,
    day: CitizenDay?,
    city: City,
): Citizen {
    val life = person.life ?: return person
    val activity = day?.activities?.firstOrNull { it.need == Need.EDUCATION && it.scheduled }
    val attended =
        activity != null &&
            day.legs.any { leg ->
                !leg.returning && leg.need == Need.EDUCATION && leg.arrival != null && leg.arrival + activity.stay < 1440 &&
                    person.acceptsEducation(city.tiles[leg.trace.destination].building) && (life.study == null || leg.trace.destination == life.study.campus)
            }
    var knowledge = life.knowledge
    if (life.stage in listOf(LifeStage.WORKER, LifeStage.RETIRED)) knowledge -= .0015 / life.learningAbility
    if (attended) {
        knowledge += (
            if (life.stage == LifeStage.SCHOOL) {
                .0225
            } else if (life.stage == LifeStage.STUDENT) {
                .018
            } else {
                .03
            }
        ) * life.learningAbility
    }
    // A museum leisure visit also teaches; daily cultural gains are capped, not multiplied by short visits.
    if (!attended && life.stage in listOf(LifeStage.WORKER, LifeStage.RETIRED) && day?.legs.orEmpty().any { it.need == Need.LEISURE && !it.returning && it.arrival != null && it.arrival + 45 < 1440 && city.tiles[it.trace.destination].building == "museum" }) knowledge += .0225 * life.learningAbility
    var next = life.copy(knowledge = knowledge.coerceIn(0.0, if (life.stage == LifeStage.SCHOOL) 100.0 else 150.0))
    var study = life.study ?: return person.copy(life = next)
    if (activity != null) {
        val streak = if (attended) min(30, study.presenceStreak + 1) else 0
        val absent = if (attended) 0 else min(30, study.absenceStreak + 1)
        val backlog = if (attended) max(0, study.backlog - 1) else min(20, study.backlog + if (absent >= 3) 1 else 0)
        val gain = if (attended) (0.8 + .2 * life.learningAbility + life.knowledge / 500) * (1 + streak * .005) * (1 - backlog * .01) else 0.0
        study =
            study.copy(
                points = study.points + gain,
                scheduledDays = study.scheduledDays + 1,
                attendedDays = study.attendedDays + if (attended) 1 else 0,
                presenceStreak = streak,
                absenceStreak = absent,
                backlog = backlog,
            )
    }
    val end = dateDay(epoch.plusDays(study.started.toLong()).plusYears(1))
    if (city.nextDay() + 1 >= end) {
        val pass = study.points >= LifeRules.ANNUAL_PASS_POINTS && study.scheduledDays > 0 && study.attendedDays.toDouble() / study.scheduledDays >= LifeRules.MINIMUM_ATTENDANCE
        next = next.copy(lastExam = "${study.course.label}, année ${study.year} : ${if (pass) "réussite" else "échec"} (%.1f points, %d/%d présences)".format(study.points, study.attendedDays, study.scheduledDays))
        next =
            when {
                pass && study.year == study.course.years -> next.copy(stage = LifeStage.WORKER, study = null, diploma = study.course, lastSalary = study.course.salary)
                !pass && study.repeated -> next.copy(stage = LifeStage.WORKER, study = null, lastSalary = 50.0)
                else -> next.copy(study = Study(study.course, study.campus, year = study.year + if (pass) 1 else 0, started = end, repeated = !pass))
            }
    } else {
        next = next.copy(study = study)
    }
    return person.copy(life = next)
}

/** UI aggregates are derived from citizens, avoiding a second persisted source of truth. */
fun City.educationByAge(): Map<String, Pair<Int, Double>> {
    val ranges = listOf(0..2, 3..17, 18..24, 25..44, 45..64, 65..120)
    val counts = IntArray(ranges.size)
    val totals = DoubleArray(ranges.size)
    for (home in households) {
        for (person in home.people) {
            val index = ranges.indexOfFirst { person.age in it }
            if (index >= 0) {
                counts[index]++
                totals[index] += person.life?.knowledge ?: 0.0
            }
        }
    }
    return ranges.indices.associate { i -> "${ranges[i].first}–${ranges[i].last}" to (counts[i] to if (counts[i] == 0) 0.0 else totals[i] / counts[i]) }
}
