package city.core

import kotlinx.serialization.Serializable

@Serializable
enum class Sex { FEMALE, MALE }

@Serializable
enum class LifeStage(
    val label: String,
) {
    CHILD("Petite enfance"),
    SCHOOL("École"),
    APPLICANT("Orientation"),
    STUDENT("Études supérieures"),
    WORKER("Travailleur"),
    RETIRED("Retraité"),
}

@Serializable
enum class Course(
    val label: String,
    val minimumKnowledge: Double,
    val years: Int,
    val salary: Double,
) {
    TEACHING("Enseignement", 60.0, 3, 65.0),
    IT("Informatique", 70.0, 3, 75.0),
    LAW("Droit", 80.0, 5, 85.0),
    MEDICINE("Médecine", 90.0, 7, 95.0),
}

@Serializable
data class Study(
    val course: Course,
    val campus: Int,
    val year: Int = 1,
    val started: Int,
    val points: Double = 0.0,
    val scheduledDays: Int = 0,
    val attendedDays: Int = 0,
    val presenceStreak: Int = 0,
    val absenceStreak: Int = 0,
    val backlog: Int = 0,
    val repeated: Boolean = false,
)

/** Additive save migration: null on old citizens, initialized once using the city's seed and date. */
@Serializable
data class CitizenLife(
    val birthDay: Int,
    val sex: Sex,
    val knowledge: Double,
    val learningAbility: Double,
    val stage: LifeStage,
    val study: Study? = null,
    val diploma: Course? = null,
    val lastSalary: Double = 0.0,
    val pension: Double = 0.0,
    val parents: List<Long> = emptyList(),
    val ancestors: List<Long> = emptyList(),
    val partner: Long? = null,
    val pregnancyDue: Int? = null,
    val pregnancyFather: Long? = null,
    val pregnancyAncestors: List<Long> = emptyList(),
    val children: Int = 0,
    val lastExam: String = "",
    val studentRelationship: StudentRelationship? = null,
)

/** Initial tuning, expressed as probabilities per month/year rather than per simulation frame. */
object LifeRules {
    const val UNIVERSITY_CHANCE = .65
    const val RELATIONSHIP_CHANCE = .04
    const val MOVE_CHANCE = .02
    const val COUPLE_MOVE_CHANCE = .15
    const val ANNUAL_PASS_POINTS = 190.0
    const val MINIMUM_ATTENDANCE = .70
}

fun CitizenLife.validate(
    person: Citizen,
    city: City,
) {
    require(birthDay <= city.nextDay() && knowledge.isFinite() && knowledge in 0.0..150.0 && learningAbility.isFinite() && learningAbility in .5..1.5)
    require(lastSalary.isFinite() && lastSalary >= 0 && pension.isFinite() && pension >= 0 && children >= 0)
    require(parents.size <= 2 && parents.distinct().size == parents.size)
    require((parents + ancestors + pregnancyAncestors + listOfNotNull(partner, pregnancyFather)).all { it in 1 until city.nextCitizenId && it != person.id })
    require((pregnancyDue == null) == (pregnancyFather == null))
    require(pregnancyDue == null || sex == Sex.FEMALE)
    require((stage == LifeStage.STUDENT) == (study != null))
    studentRelationship?.let {
        require(partner == null && it.partner in 1 until city.nextCitizenId && it.partner != person.id)
        require(it.started in 0..city.nextDay() && it.strength.isFinite() && it.strength in 0.0..100.0)
    }
    study?.let {
        require(it.campus in city.tiles.indices && it.year in 1..it.course.years && it.started in 0..city.nextDay())
        require(it.points.isFinite() && it.points >= 0 && it.scheduledDays >= 0 && it.attendedDays in 0..it.scheduledDays)
        require(it.presenceStreak in 0..30 && it.absenceStreak in 0..30 && it.backlog in 0..20)
    }
}
