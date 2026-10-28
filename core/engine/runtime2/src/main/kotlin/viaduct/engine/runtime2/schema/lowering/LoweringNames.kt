package viaduct.engine.runtime2.schema.lowering

import viaduct.graphql.schema.ViaductSchema

internal const val LOWERING_SYNTHETIC_NAME_TOKEN = "V_A"
internal const val ALL_SOURCE_OBJECTS_TYPE = "V_A_AllSourceObjects"
internal const val LOWERED_TYPENAME_FIELD = "V_A_typename"
internal const val VIADUCT_IGNORE_SYMBOL = ViaductSchema.VIADUCT_IGNORE_SYMBOL

private val reservedNameTokenPattern = Regex("(^|_)${LOWERING_SYNTHETIC_NAME_TOKEN}(_|$)")

internal fun containsSyntheticNameToken(name: String): Boolean = reservedNameTokenPattern.containsMatchIn(name)
