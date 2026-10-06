package viaduct.engine.runtime2.resolution.inclusion

internal val T3_INPUT_FRAGMENT =
    """
    fragment T3Input on Query {
      left: seed
        @include(if: ${'$'}includeLeft)
        @skip(if: ${'$'}skipLeft)
      right: seed
        @include(if: ${'$'}includeRight)
        @skip(if: ${'$'}skipRight)
    }
    """.trimIndent()

internal data class T3Vector(
    val includeLeft: Boolean,
    val skipLeft: Boolean,
    val includeRight: Boolean,
    val skipRight: Boolean,
) {
    val bindings: Map<String, Boolean>
        get() =
            mapOf(
                "includeLeft" to includeLeft,
                "skipLeft" to skipLeft,
                "includeRight" to includeRight,
                "skipRight" to skipRight,
            )

    companion object {
        val variableNames: Set<String> =
            setOf("includeLeft", "skipLeft", "includeRight", "skipRight")

        val all: List<T3Vector> =
            (0 until 16).map(::fromBits)

        fun fromBits(bits: Int): T3Vector =
            T3Vector(
                includeLeft = bits and 1 != 0,
                skipLeft = bits and 2 != 0,
                includeRight = bits and 4 != 0,
                skipRight = bits and 8 != 0,
            )
    }
}

/** A deliberately bespoke oracle which does not construct or evaluate InclusionCondition. */
internal class T3Oracle(vector: T3Vector) {
    private val leftIncluded = vector.includeLeft && !vector.skipLeft
    private val rightIncluded = vector.includeRight && !vector.skipRight

    val inputAliases: Set<String> =
        buildSet {
            if (leftIncluded) add("left")
            if (rightIncluded) add("right")
        }

    val seedActive: Boolean = leftIncluded || rightIncluded

    val inputSnapshot: InputSnapshot =
        InputSnapshot(
            paths = inputAliases.mapTo(linkedSetOf()) { alias -> listOf(alias) },
            intValues = inputAliases.associate { alias -> listOf(alias) to SEED_VALUE },
        )

    fun fingerprintAt(depth: Int): Int =
        t3DepthTag(depth) or
            (if (leftIncluded) SEED_VALUE shl LEFT_SHIFT else 0) or
            (if (rightIncluded) SEED_VALUE shl RIGHT_SHIFT else 0)
}

internal const val LEFT_SHIFT = 0
internal const val RIGHT_SHIFT = 4

internal fun t3DepthTag(depth: Int): Int = (depth + 1) shl 8
