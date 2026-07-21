package viaduct.tenant.runtime.testing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map

data class NormalizedValue(
    val raw: Any?,
    val normalized: Any?,
)

data class NormalizedVariables(
    val raw: Map<String, Any?>,
    val normalized: Map<String, Any?>,
)

fun Arb.Companion.normalizedVariables(
    leaf: Arb<NormalizedValue>,
    depth: Int = 3,
): Arb<NormalizedVariables> =
    Arb.list(normalizedValue(leaf, depth), 1..4).map { fields ->
        NormalizedVariables(
            raw = fields.mapIndexed { index, case -> "field$index" to case.raw }.toMap(),
            normalized = fields.mapIndexed { index, case -> "field$index" to case.normalized }.toMap(),
        )
    }

private fun normalizedValue(
    leaf: Arb<NormalizedValue>,
    depth: Int,
): Arb<NormalizedValue> {
    if (depth == 0) {
        return leaf
    }

    val nested = normalizedValue(leaf, depth - 1)
    return Arb.choice(
        leaf,
        Arb.list(nested, 0..3).map { values ->
            NormalizedValue(
                raw = values.map { it.raw },
                normalized = values.map { it.normalized },
            )
        },
        Arb.list(nested, 0..3).map { values ->
            NormalizedValue(
                raw = values.map { it.raw }.toTypedArray(),
                normalized = values.map { it.normalized },
            )
        },
        Arb.list(nested, 0..3).map { values ->
            NormalizedValue(
                raw = values.mapIndexed { index, case -> "key$index" to case.raw }.toMap(),
                normalized = values.mapIndexed { index, case -> "key$index" to case.normalized }.toMap(),
            )
        },
    )
}
