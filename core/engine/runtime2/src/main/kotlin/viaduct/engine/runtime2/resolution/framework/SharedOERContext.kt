package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/** The occurrence, passive source, and closed value-selection projection for one OER. */
class SharedOERContext internal constructor(
    val occurrence: OEROccurrence,
    val source: EngineObjectData.Sync,
    val closedValueSelections: ObjectSelectionForest,
) {
    /** Whether this context has any closed value selections. */
    fun isDemanded(): Boolean = !closedValueSelections.isEmpty()

    companion object {
        /** Temporary undemanded Query context for resolver families awaiting paired-OER preparation. */
        internal fun undemandedQuery(queryType: ViaductSchema.Object): SharedOERContext {
            val result = ObjectEngineResult.of(queryType, mutable = true)
            return SharedOERContext(
                occurrence = OEROccurrence(result, emptyList(), result),
                source = engineObjectDataOf(queryType),
                closedValueSelections = selectionForestOf().merge(queryType),
            )
        }
    }
}
