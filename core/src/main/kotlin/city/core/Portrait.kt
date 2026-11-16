package city.core

/** Stable resource keys. Age only selects a row; it never redraws a person's identity. */
enum class PortraitAge(
    val resource: String,
) {
    CHILD("child"),
    TEEN("teen"),
    ADULT("adult"),
    MATURE("mature"),
    SENIOR("senior"),
    ELDER("elder"),
    ;

    companion object {
        fun forYears(age: Int): PortraitAge =
            when {
                age < 13 -> CHILD
                age < 20 -> TEEN
                age < 45 -> ADULT
                age < 65 -> MATURE
                age < 80 -> SENIOR
                else -> ELDER
            }
    }
}

/** Ordinals and resource names are part of the persisted v1 appearance format. */
enum class PortraitPart(
    val resource: String,
) {
    FRAME("frame"),
    FACE("face"),
    EARS("ears"),
    BROWS("brows"),
    EYES("eyes"),
    NOSE("nose"),
    MOUTH("mouth"),
}

/**
 * v1: skin family in bits 0..1, sex in bit 2, seven 3-bit variant IDs in bits
 * 3..23, version in bits 24..27. No date, age, home, name, JVM RNG or hashCode.
 * Keep variant IDs stable when expanding the artwork bank.
 */
object PortraitCodes {
    const val VARIANTS = 5
    const val FAMILIES = 3
    private const val VERSION = 1L shl 24

    fun create(
        id: Long,
        sex: Sex,
    ): Long {
        var code = VERSION or (noise(id, 1301, 0) % FAMILIES).toLong() or (sex.ordinal.toLong() shl 2)
        for (part in PortraitPart.entries) {
            code = code or ((noise(id, 1311 + part.ordinal, 0) % VARIANTS).toLong() shl (3 + part.ordinal * 3))
        }
        return code
    }

    fun family(code: Long): Int = (code and 3).toInt()

    fun sex(code: Long): Sex = Sex.entries[((code ushr 2) and 1).toInt()]

    fun variant(
        code: Long,
        part: PortraitPart,
    ): Int = ((code ushr (3 + part.ordinal * 3)) and 7).toInt()

    fun withSex(
        code: Long,
        sex: Sex,
    ): Long = (code and 4L.inv()) or (sex.ordinal.toLong() shl 2)

    fun valid(code: Long): Boolean = code ushr 24 == 1L && family(code) < FAMILIES && PortraitPart.entries.all { variant(code, it) < VARIANTS }

    fun withVariant(
        code: Long,
        part: PortraitPart,
        variant: Int,
    ): Long {
        require(valid(code) && variant in 0 until VARIANTS)
        val shift = 3 + part.ordinal * 3
        return (code and (7L shl shift).inv()) or (variant.toLong() shl shift)
    }
}
