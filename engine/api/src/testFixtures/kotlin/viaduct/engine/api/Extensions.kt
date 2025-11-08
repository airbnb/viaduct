package viaduct.engine.api

/** return true if the provided [EngineObjectData.Sync]s describe the same object with the same data */
fun engineObjectsAreEquivalent(
    a: EngineObjectData.Sync,
    b: EngineObjectData.Sync
): Boolean = a.graphQLObjectType === b.graphQLObjectType && a.asMap == b.asMap

private val Any?.asComparableValue: Any? get() =
    when (this) {
        null -> null
        is EngineObjectData.Sync -> {
            getSelections().associate { sel ->
                sel to get(sel).asComparableValue
            }
        }
        is List<*> -> map { it.asComparableValue }
        else -> this
    }

/** return a Map for an [EngineObjectData.Sync], with all nested [EngineObjectData.Sync]s unwrapped */
@Suppress("UNCHECKED_CAST")
val EngineObjectData.Sync.asMap: Map<String, Any?> get() =
    this.asComparableValue as Map<String, Any?>
