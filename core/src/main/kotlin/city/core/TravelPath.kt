package city.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import java.util.RandomAccess

/** Immutable primitive storage behind the existing List API; a node is cell * TRAVEL_MODE_COUNT + mode.ordinal. */
@Serializable(with = TravelPath.Serializer::class)
class TravelPath private constructor(
    private val nodeStorage: IntValues,
    private val minuteStorage: IntValues,
) {
    constructor(nodes: List<Int>, minutes: List<Int>) : this(IntValues.snapshot(nodes), IntValues.snapshot(minutes))

    constructor(nodes: IntArray, minutes: IntArray) : this(IntValues(nodes.copyOf()), IntValues(minutes.copyOf()))

    val nodes: List<Int> get() = nodeStorage
    val minutes: List<Int> get() = minuteStorage
    val duration: Int get() = minuteStorage.values.last()
    val destination: Int get() = nodeStorage.values.last() / TRAVEL_MODE_COUNT

    fun copy(
        nodes: List<Int> = this.nodes,
        minutes: List<Int> = this.minutes,
    ): TravelPath = TravelPath(nodes, minutes)

    operator fun component1(): List<Int> = nodes

    operator fun component2(): List<Int> = minutes

    override fun equals(other: Any?): Boolean = this === other || (other is TravelPath && nodeStorage.values.contentEquals(other.nodeStorage.values) && minuteStorage.values.contentEquals(other.minuteStorage.values))

    override fun hashCode(): Int = 31 * nodeStorage.values.contentHashCode() + minuteStorage.values.contentHashCode()

    override fun toString(): String = "TravelPath(nodes=$nodes, minutes=$minutes)"

    /** Neither constructor inputs nor collection views can mutate a published registry key. */
    private class IntValues(
        val values: IntArray,
    ) : AbstractList<Int>(),
        RandomAccess {
        override val size: Int get() = values.size

        override fun get(index: Int): Int = values[index]

        override fun hashCode(): Int = values.contentHashCode()

        override fun equals(other: Any?): Boolean = if (other is IntValues) values.contentEquals(other.values) else super.equals(other)

        companion object {
            fun snapshot(values: List<Int>): IntValues = if (values is IntValues) values else IntValues(values.toIntArray())
        }
    }

    /** Keep the existing JSON schema while decoding integers directly into primitive storage. */
    object Serializer : KSerializer<TravelPath> {
        private val ints = IntArraySerializer()
        override val descriptor =
            buildClassSerialDescriptor("city.core.TravelPath") {
                element("nodes", ints.descriptor)
                element("minutes", ints.descriptor)
            }

        override fun serialize(
            encoder: Encoder,
            value: TravelPath,
        ) {
            encoder.encodeStructure(descriptor) {
                encodeSerializableElement(descriptor, 0, ints, value.nodeStorage.values)
                encodeSerializableElement(descriptor, 1, ints, value.minuteStorage.values)
            }
        }

        override fun deserialize(decoder: Decoder): TravelPath =
            decoder.decodeStructure(descriptor) {
                var nodes: IntArray? = null
                var minutes: IntArray? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        0 -> nodes = decodeSerializableElement(descriptor, 0, ints)
                        1 -> minutes = decodeSerializableElement(descriptor, 1, ints)
                        else -> throw SerializationException("Unknown TravelPath field: $index")
                    }
                }
                // The decoder owns these arrays exclusively; do not copy them a second time.
                TravelPath(
                    IntValues(nodes ?: throw SerializationException("Missing TravelPath.nodes")),
                    IntValues(minutes ?: throw SerializationException("Missing TravelPath.minutes")),
                )
            }
    }
}
