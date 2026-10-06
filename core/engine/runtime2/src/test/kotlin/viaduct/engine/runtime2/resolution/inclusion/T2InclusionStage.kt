package viaduct.engine.runtime2.resolution.inclusion

import viaduct.engine.api.EngineObjectData

internal val T2_INPUT_FRAGMENT =
    """
    fragment T2Input on Query {
      first: t3
        @include(if: ${'$'}includeFirst)
        @skip(if: ${'$'}skipFirst) {
        i @include(if: ${'$'}includeSecond)
        n {
          i @skip(if: ${'$'}skipSecond)
          n {
            i @include(if: ${'$'}includeSecond)
            n @include(if: ${'$'}includeSecond) {
              i
            }
          }
        }
      }
      second: t3
        @include(if: ${'$'}includeSecond)
        @skip(if: ${'$'}skipSecond) {
        i @include(if: ${'$'}includeFirst)
        n {
          i @skip(if: ${'$'}skipFirst)
          n {
            i @include(if: ${'$'}includeFirst)
            n @skip(if: ${'$'}skipFirst) {
              i
            }
          }
        }
      }
    }
    """.trimIndent()

internal data class T2Vector(
    val includeFirst: Boolean,
    val skipFirst: Boolean,
    val includeSecond: Boolean,
    val skipSecond: Boolean,
) {
    val bindings: Map<String, Boolean>
        get() =
            mapOf(
                "includeFirst" to includeFirst,
                "skipFirst" to skipFirst,
                "includeSecond" to includeSecond,
                "skipSecond" to skipSecond,
            )

    companion object {
        val variableNames: Set<String> =
            setOf("includeFirst", "skipFirst", "includeSecond", "skipSecond")

        fun fromBits(bits: Int): T2Vector =
            T2Vector(
                includeFirst = bits and 1 != 0,
                skipFirst = bits and 2 != 0,
                includeSecond = bits and 4 != 0,
                skipSecond = bits and 8 != 0,
            )
    }
}

internal data class T2CombinationVector(
    val t3: T3Vector,
    val t2: T2Vector,
) {
    companion object {
        val all: List<T2CombinationVector> =
            (0 until 256).map { bits ->
                T2CombinationVector(
                    t3 = T3Vector.fromBits(bits),
                    t2 = T2Vector.fromBits(bits shr 4),
                )
            }
    }
}

/** The t2 oracle directly applies the fixed fragment's directive truth table. */
internal class T2Oracle(vector: T2CombinationVector) {
    private val t3Oracle = T3Oracle(vector.t3)
    private val t2 = vector.t2
    private val first = t2.includeFirst && !t2.skipFirst
    private val second = t2.includeSecond && !t2.skipSecond

    val t3Active: Boolean = first || second
    val seedActive: Boolean = t3Active && t3Oracle.seedActive
    val t3Input: InputSnapshot = t3Oracle.inputSnapshot
    val t2Input: InputSnapshot = expectedT2Input()

    fun fingerprintAt(depth: Int): Int {
        var fingerprint = t2DepthTag(depth)
        t2Input.intValues.forEach { (path, value) ->
            if (path.depth() == depth) {
                fingerprint = fingerprint or value or t2ReadTag(path.first(), depth)
            }
        }
        return fingerprint
    }

    fun contributingAliasesAt(depth: Int): Set<String> =
        t2Input.intValues.keys
            .filterTo(linkedSetOf()) { path -> path.depth() == depth }
            .mapTo(linkedSetOf()) { path -> path.first() }

    private fun expectedT2Input(): InputSnapshot =
        InputSnapshotBuilder()
            .apply {
                objectPath(first, "first")
                intPath(first && t2.includeSecond, t3Oracle.fingerprintAt(0), "first", "i")
                val firstN1 = first
                objectPath(firstN1, "first", "n")
                intPath(
                    firstN1 && !t2.skipSecond,
                    t3Oracle.fingerprintAt(1),
                    "first",
                    "n",
                    "i",
                )
                val firstN2 = firstN1
                objectPath(firstN2, "first", "n", "n")
                intPath(
                    firstN2 && t2.includeSecond,
                    t3Oracle.fingerprintAt(2),
                    "first",
                    "n",
                    "n",
                    "i",
                )
                val firstN3 = firstN2 && t2.includeSecond
                objectPath(firstN3, "first", "n", "n", "n")
                intPath(
                    firstN3,
                    t3Oracle.fingerprintAt(3),
                    "first",
                    "n",
                    "n",
                    "n",
                    "i",
                )

                objectPath(second, "second")
                intPath(second && t2.includeFirst, t3Oracle.fingerprintAt(0), "second", "i")
                val secondN1 = second
                objectPath(secondN1, "second", "n")
                intPath(
                    secondN1 && !t2.skipFirst,
                    t3Oracle.fingerprintAt(1),
                    "second",
                    "n",
                    "i",
                )
                val secondN2 = secondN1
                objectPath(secondN2, "second", "n", "n")
                intPath(
                    secondN2 && t2.includeFirst,
                    t3Oracle.fingerprintAt(2),
                    "second",
                    "n",
                    "n",
                    "i",
                )
                val secondN3 = secondN2 && !t2.skipFirst
                objectPath(secondN3, "second", "n", "n", "n")
                intPath(
                    secondN3,
                    t3Oracle.fingerprintAt(3),
                    "second",
                    "n",
                    "n",
                    "n",
                    "i",
                )
            }.build()
}

internal fun t2FingerprintFromInput(
    input: EngineObjectData.Sync,
    depth: Int,
): Int {
    var fingerprint = t2DepthTag(depth)
    T2_INPUT_I_PATHS.filter { it.depth() == depth }.forEach { path ->
        val value = input.valueAtIfPresent(path) ?: return@forEach
        fingerprint = fingerprint or value or t2ReadTag(path.first(), depth)
    }
    return fingerprint
}

private fun t2DepthTag(depth: Int): Int = 1 shl (20 + depth)

private fun t2ReadTag(
    alias: String,
    depth: Int,
): Int =
    1 shl
        when (alias) {
            "first" -> 12 + depth
            "second" -> 16 + depth
            else -> error("Unknown t2 input alias: $alias")
        }

private val T2_INPUT_I_PATHS: List<List<String>> = iPathsForAliases("first", "second")
