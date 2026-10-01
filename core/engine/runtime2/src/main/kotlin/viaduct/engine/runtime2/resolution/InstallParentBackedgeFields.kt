package viaduct.engine.runtime2.resolution

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Installs structural backedges without reopening resolution of the containing object. */
internal fun OEROccurrence.installParentBackedgeFields(
    operation: SharedOperationContext<*>,
    keys: Collection<ObjectEngineResult.ParentKey>,
) {
    if (keys.isEmpty()) return
    val containing = requireNotNull(parent) { "Parent demand at $path has no containing occurrence" }
    val producer = path.filterIsInstance<ObjectEngineResult.ObjectKey>().lastOrNull()?.field
    keys.forEach { key ->
        require(operation.world.parentFieldRelations[key.field] == producer) {
            "Parent field ${key.field.name} is not inverse to its containing producer occurrence"
        }
        target.setCellValue(key, containing.target)
    }
}
