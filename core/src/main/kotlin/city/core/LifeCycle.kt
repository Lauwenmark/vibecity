package city.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

internal fun dateDay(date: LocalDate): Int = ChronoUnit.DAYS.between(epoch, date).toInt()

internal fun draw(
    city: City,
    id: Long,
    salt: Int,
): Double = noise(city.seed xor id, salt, city.nextDay()) / 2147483648.0

fun mortalityProbability(age: Int): Double {
    if (age >= 121) return 1.0
    val annual = if (age < 60) .0002 else (.005 * 2.0.pow((age - 60) / 8.0)).coerceAtMost(.95)
    return 1 - (1 - annual).pow(1.0 / 365.2425)
}

val Citizen.isStudent: Boolean get() = life?.stage?.let { it == LifeStage.SCHOOL || it == LifeStage.STUDENT } ?: (age in 3..17)
val Citizen.isWorker: Boolean get() = life?.stage?.let { it == LifeStage.WORKER } ?: (age in 18..64)
val Citizen.dailyIncome: Double get() =
    life?.let {
        when (it.stage) {
            LifeStage.WORKER -> it.lastSalary
            LifeStage.RETIRED -> it.pension
            else -> 0.0
        }
    } ?: if (age >= 65) {
        100.0 / 3
    } else if (age >= 18) {
        50.0
    } else {
        0.0
    }

internal fun Citizen.initialized(city: City): Citizen {
    if (life != null) return this
    val n = noise(city.seed xor id, 611, 0)
    val birthday = epoch.plusDays(city.nextDay().toLong()).minusYears(age.toLong()).minusDays((n % 365).toLong())
    val ca = .5 + (n % 1001) / 1000.0
    val ni = (5 + n % 6 + (age - 3).coerceIn(0, 15) * 5 * ca).coerceIn(0.0, 120.0)
    val diploma =
        Course.entries
            .filter { ni >= it.minimumKnowledge }
            .takeIf { age >= 25 && n % 3 == 0 }
            ?.let { it.getOrNull(n % max(1, it.size)) }
    val salary = diploma?.salary ?: 50.0
    return copy(
        life =
            CitizenLife(
                dateDay(birthday),
                if (n % 2 == 0) Sex.FEMALE else Sex.MALE,
                ni,
                ca,
                when {
                    age < 3 -> LifeStage.CHILD
                    age < 18 -> LifeStage.SCHOOL
                    age >= 65 -> LifeStage.RETIRED
                    else -> LifeStage.WORKER
                },
                diploma = diploma,
                lastSalary = if (age >= 18) salary else 0.0,
                pension = if (age >= 65) salary * 2 / 3 else 0.0,
            ),
    )
}

/** Tile population remains the economic immigration target; life events update it immediately. */
internal fun reconcileCitizens(
    city: City,
    catalog: Catalog,
): City {
    val old = city.households.associateBy { it.cell }
    var nextId = city.nextCitizenId
    val homes =
        city.tiles.mapIndexedNotNull { i, tile ->
            val count =
                if (tile.zone.residential) {
                    tile.occupants
                } else if (tile.anchor == i && tile.building != null) {
                    tile.residentialOccupancy ?: catalog[tile.building].residents
                } else {
                    0
                }
            if (count <= 0) return@mapIndexedNotNull null
            val retained =
                old[i]
                    ?.people
                    .orEmpty()
                    .take(count)
                    .map { it.initialized(city) }
            val arrivals =
                List(count - retained.size) {
                    val id = nextId++
                    val n = noise(city.seed xor id, i, 0)
                    Citizen(id, "${FIRST_NAMES[n % FIRST_NAMES.size]} ${LAST_NAMES[(n / FIRST_NAMES.size) % LAST_NAMES.size]} #$id", 6 + n % 80, position = i * TRAVEL_MODE_COUNT).initialized(city)
                }
            // Arrival batches are not assumed to be families. Newborns carry explicit genealogy.
            Household(i, retained + arrivals, old[i]?.paths.orEmpty())
        }
    return city.copy(households = homes, nextCitizenId = nextId)
}

internal val FIRST_NAMES = listOf("Camille", "Alex", "Louise", "Nora", "Émile", "Sami", "Léa", "Arthur", "Inès", "Hugo", "Maya", "Noé")
private val LAST_NAMES = listOf("Martin", "Dubois", "Lambert", "Peeters", "Laurent", "Simon", "Diallo", "Moreau", "Nguyen", "Janssens")

/** Demolition evicts residents immediately, even while paused; genealogy retains departed IDs. */
internal fun evictHouseholds(
    city: City,
    cells: Set<Int>,
): City {
    val removed = city.households.filter { it.cell in cells }
    if (removed.isEmpty()) return city
    val departed = removed.flatMap { it.people }.map { it.id }.toSet()
    val households =
        city.households.filter { it.cell !in cells }.map { home ->
            val people =
                home.people.map { person ->
                    val life = person.life
                    if (life != null && (life.partner in departed || life.studentRelationship?.partner in departed)) {
                        person.copy(
                            life =
                                life.copy(
                                    partner = life.partner?.takeUnless { it in departed },
                                    studentRelationship = life.studentRelationship?.takeUnless { it.partner in departed },
                                ),
                        )
                    } else {
                        person
                    }
                }
            if (people == home.people) home else home.copy(people = people)
        }
    return city.copy(households = households, stats = city.stats.copy(population = households.sumOf { it.people.size }))
}

/** Deaths precede birthdays/transitions, births and monthly household decisions; mobility is rebuilt afterwards. */
internal fun beginLifeDay(
    input: City,
    catalog: Catalog,
): City {
    val city = reconcileCitizens(input, catalog)
    val today = epoch.plusDays(city.nextDay().toLong())
    val homes =
        city.households.map { home ->
            home.copy(
                people =
                    home.people.mapNotNull { person ->
                        val life = requireNotNull(person.life)
                        val birth = epoch.plusDays(life.birthDay.toLong())
                        val years = today.year - birth.year
                        val age = years - if (today < birth.plusYears(years.toLong())) 1 else 0
                        if (draw(city, person.id, 703) < mortalityProbability(age)) return@mapNotNull null
                        val next =
                            when {
                                age >= 65 && life.stage != LifeStage.RETIRED -> life.copy(stage = LifeStage.RETIRED, study = null, pension = max(20.0, life.lastSalary * 2 / 3))
                                age >= 18 && life.stage in listOf(LifeStage.CHILD, LifeStage.SCHOOL) -> life.copy(stage = LifeStage.APPLICANT)
                                age >= 3 && life.stage == LifeStage.CHILD -> life.copy(stage = LifeStage.SCHOOL)
                                else -> life
                            }
                        person.copy(
                            age = age,
                            life = next,
                            routes = if (next.stage != life.stage) emptyList() else person.routes,
                            wellbeing = if (next.stage != life.stage) person.wellbeing.copy(workplace = null) else person.wellbeing,
                        )
                    },
            )
        }
    var result = syncPopulation(city, homes)
    result = repairPartners(result)
    result = deliverBabies(result)
    if (today.dayOfMonth == 1) result = monthlyFamilies(result, catalog)
    return result
}

/** No hidden respawn after death/birth/move; retained route registries stay attached to their cell. */
internal fun syncPopulation(
    city: City,
    homes: List<Household>,
): City {
    val byCell = homes.associateBy { it.cell }
    val oldCells = city.households.map { it.cell }.toSet()
    val tiles =
        city.tiles.mapIndexed { i, tile ->
            if (i !in byCell && i !in oldCells) {
                tile
            } else {
                val count = byCell[i]?.people?.size ?: 0
                if (tile.zone.residential) tile.copy(occupants = count) else tile.copy(residentialOccupancy = count)
            }
        }
    return city.copy(
        tiles = tiles,
        households = homes.filter { it.people.isNotEmpty() }.sortedBy { it.cell },
        mobility = null,
        stats = city.stats.copy(population = homes.sumOf { it.people.size }),
    )
}

internal fun repairPartners(city: City): City {
    val people = city.households.flatMap { it.people }.associateBy { it.id }
    return city.copy(
        households =
            city.households.map { home ->
                home.copy(
                    people =
                        home.people.map { p ->
                            val life = p.life
                            if (life?.partner != null && people[life.partner]?.life?.partner != p.id) p.copy(life = life.copy(partner = null)) else p
                        },
                )
            },
    )
}
