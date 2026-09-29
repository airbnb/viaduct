package semantics.resolver26.inclusion

import viaduct.engine.api.EngineObjectData

internal val T1_INPUT_FRAGMENT =
    """
    fragment T1Input on Query {
      via2: t2 @include(if: ${'$'}p) @skip(if: ${'$'}q) {
        i @include(if: ${'$'}r)
        n {
          i @skip(if: ${'$'}r)
          n {
            i @include(if: ${'$'}s)
            n @include(if: ${'$'}r) {
              i @skip(if: ${'$'}s)
            }
          }
        }
      }
      directFirst: t3 @include(if: ${'$'}q) @skip(if: ${'$'}r) {
        i @skip(if: ${'$'}s)
        n {
          i @include(if: ${'$'}p)
          n {
            i @include(if: ${'$'}s)
            n @include(if: ${'$'}p) {
              i @skip(if: ${'$'}s)
            }
          }
        }
      }
      directSecond: t3 @include(if: ${'$'}s) @skip(if: ${'$'}r) {
        i @include(if: ${'$'}q)
        n {
          i @include(if: ${'$'}p)
          n {
            i @skip(if: ${'$'}q)
            n @skip(if: ${'$'}p) {
              i @include(if: ${'$'}q)
            }
          }
        }
      }
    }
    """.trimIndent()

internal data class T1Vector(
    val p: Boolean,
    val q: Boolean,
    val r: Boolean,
    val s: Boolean,
) {
    val bindings: Map<String, Boolean>
        get() = mapOf("p" to p, "q" to q, "r" to r, "s" to s)

    companion object {
        val variableNames: Set<String> = setOf("p", "q", "r", "s")
        val none = T1Vector(p = false, q = false, r = false, s = false)

        fun fromBits(bits: Int): T1Vector =
            T1Vector(
                p = bits and 1 != 0,
                q = bits and 2 != 0,
                r = bits and 4 != 0,
                s = bits and 8 != 0,
            )
    }
}

internal data class T1CombinationVector(
    val t3: T3Vector,
    val t2: T2Vector,
    val t1: T1Vector,
) {
    companion object {
        val all: List<T1CombinationVector> =
            (0 until 4096).map { bits ->
                T1CombinationVector(
                    t3 = T3Vector.fromBits(bits),
                    t2 = T2Vector.fromBits(bits shr 4),
                    t1 = T1Vector.fromBits(bits shr 8),
                )
            }

        fun from(vector: T2CombinationVector): T1CombinationVector = T1CombinationVector(vector.t3, vector.t2, T1Vector.none)
    }
}

/** The t1 oracle keeps direct t3 occurrences separate from the t2-mediated occurrence. */
internal class T1Oracle(vector: T1CombinationVector) {
    private val t3Oracle = T3Oracle(vector.t3)
    private val t2Oracle = T2Oracle(T2CombinationVector(vector.t3, vector.t2))
    private val t1 = vector.t1
    private val via2 = t1.p && !t1.q
    private val directFirst = t1.q && !t1.r
    private val directSecond = t1.s && !t1.r

    val t2Active: Boolean = via2
    val directT3Active: Boolean = directFirst || directSecond
    val indirectT3Active: Boolean = via2 && t2Oracle.t3Active
    val t3Active: Boolean = directT3Active || indirectT3Active
    val seedActive: Boolean = t3Active && t3Oracle.seedActive
    val t3Input: InputSnapshot = t3Oracle.inputSnapshot
    val t2Input: InputSnapshot = t2Oracle.t2Input
    val t1Input: InputSnapshot = expectedT1Input()

    fun fingerprintAt(depth: Int): Int {
        var fingerprint = t1DepthTag(depth)
        t1Input.intValues.forEach { (path, value) ->
            if (path.depth() == depth) {
                fingerprint = fingerprint or value or t1ReadTag(path.first())
            }
        }
        return fingerprint
    }

    private fun expectedT1Input(): InputSnapshot =
        InputSnapshotBuilder()
            .apply {
                objectPath(via2, "via2")
                intPath(via2 && t1.r, t2Oracle.fingerprintAt(0), "via2", "i")
                val via2N1 = via2
                objectPath(via2N1, "via2", "n")
                intPath(
                    via2N1 && !t1.r,
                    t2Oracle.fingerprintAt(1),
                    "via2",
                    "n",
                    "i",
                )
                val via2N2 = via2N1
                objectPath(via2N2, "via2", "n", "n")
                intPath(
                    via2N2 && t1.s,
                    t2Oracle.fingerprintAt(2),
                    "via2",
                    "n",
                    "n",
                    "i",
                )
                val via2N3 = via2N2 && t1.r
                objectPath(via2N3, "via2", "n", "n", "n")
                intPath(
                    via2N3 && !t1.s,
                    t2Oracle.fingerprintAt(3),
                    "via2",
                    "n",
                    "n",
                    "n",
                    "i",
                )

                objectPath(directFirst, "directFirst")
                intPath(
                    directFirst && !t1.s,
                    t3Oracle.fingerprintAt(0),
                    "directFirst",
                    "i",
                )
                val directFirstN1 = directFirst
                objectPath(directFirstN1, "directFirst", "n")
                intPath(
                    directFirstN1 && t1.p,
                    t3Oracle.fingerprintAt(1),
                    "directFirst",
                    "n",
                    "i",
                )
                val directFirstN2 = directFirstN1
                objectPath(directFirstN2, "directFirst", "n", "n")
                intPath(
                    directFirstN2 && t1.s,
                    t3Oracle.fingerprintAt(2),
                    "directFirst",
                    "n",
                    "n",
                    "i",
                )
                val directFirstN3 = directFirstN2 && t1.p
                objectPath(directFirstN3, "directFirst", "n", "n", "n")
                intPath(
                    directFirstN3 && !t1.s,
                    t3Oracle.fingerprintAt(3),
                    "directFirst",
                    "n",
                    "n",
                    "n",
                    "i",
                )

                objectPath(directSecond, "directSecond")
                intPath(
                    directSecond && t1.q,
                    t3Oracle.fingerprintAt(0),
                    "directSecond",
                    "i",
                )
                val directSecondN1 = directSecond
                objectPath(directSecondN1, "directSecond", "n")
                intPath(
                    directSecondN1 && t1.p,
                    t3Oracle.fingerprintAt(1),
                    "directSecond",
                    "n",
                    "i",
                )
                val directSecondN2 = directSecondN1
                objectPath(directSecondN2, "directSecond", "n", "n")
                intPath(
                    directSecondN2 && !t1.q,
                    t3Oracle.fingerprintAt(2),
                    "directSecond",
                    "n",
                    "n",
                    "i",
                )
                val directSecondN3 = directSecondN2 && !t1.p
                objectPath(directSecondN3, "directSecond", "n", "n", "n")
                intPath(
                    directSecondN3 && t1.q,
                    t3Oracle.fingerprintAt(3),
                    "directSecond",
                    "n",
                    "n",
                    "n",
                    "i",
                )
            }.build()
}

internal fun t1FingerprintFromInput(
    input: EngineObjectData.Sync,
    depth: Int,
): Int {
    var fingerprint = t1DepthTag(depth)
    T1_INPUT_I_PATHS.filter { it.depth() == depth }.forEach { path ->
        val value = input.valueAtIfPresent(path) ?: return@forEach
        fingerprint = fingerprint or value or t1ReadTag(path.first())
    }
    return fingerprint
}

private fun t1DepthTag(depth: Int): Int = 1 shl (27 + depth)

private fun t1ReadTag(alias: String): Int =
    1 shl
        when (alias) {
            "via2" -> 24
            "directFirst" -> 25
            "directSecond" -> 26
            else -> error("Unknown t1 input alias: $alias")
        }

private val T1_INPUT_I_PATHS: List<List<String>> =
    iPathsForAliases("via2", "directFirst", "directSecond")
