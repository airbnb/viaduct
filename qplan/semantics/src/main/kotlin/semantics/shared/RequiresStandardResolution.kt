package semantics.shared

import model.ObjectEngineResult
import model.schemaType
import viaduct.engine.api.EngineObjectData

/** Source-owned argumentless fields suppress their standard resolver and its input demand. */
internal fun EngineObjectData.Sync.requiresStandardResolution(key: ObjectEngineResult.ObjectKey): Boolean {
    if (!isPresent(key.field.name)) return true
    require(key.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${key.field.name}"
    }
    return false
}
