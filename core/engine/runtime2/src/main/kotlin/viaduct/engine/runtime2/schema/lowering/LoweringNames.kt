package viaduct.engine.runtime2.schema.lowering

import viaduct.graphql.schema.ViaductSchema

internal const val LOWERING_SYNTHETIC_NAME_PREFIX = "_viaduct_internal__"
internal const val ALL_SOURCE_OBJECTS_TYPE = "${LOWERING_SYNTHETIC_NAME_PREFIX}AllSourceObjects"
internal const val LOWERED_TYPENAME_FIELD = "${LOWERING_SYNTHETIC_NAME_PREFIX}typename"
internal const val VIADUCT_IGNORE_SYMBOL = ViaductSchema.VIADUCT_IGNORE_SYMBOL

internal fun isSyntheticName(name: String): Boolean = name.startsWith(LOWERING_SYNTHETIC_NAME_PREFIX)
