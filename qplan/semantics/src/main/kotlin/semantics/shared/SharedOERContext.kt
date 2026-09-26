package semantics.shared

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.engineObjectDataOf
import model.merge
import model.selectionForestOf
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** The occurrence, passive source, and closed construction demand for one OER. */
class SharedOERContext internal constructor(
    val occurrence: OEROccurrence,
    val source: EngineObjectData.Sync,
    val closedDemand: ObjectSelectionForest,
) {
    /** Whether this context has any closed construction demand. */
    fun isDemanded(): Boolean = !closedDemand.isEmpty()

    companion object {
        /** Temporary undemanded Query context for resolver families awaiting paired-OER preparation. */
        internal fun undemandedQuery(queryType: ViaductSchema.Object): SharedOERContext {
            val result = ObjectEngineResult.of(queryType, mutable = true)
            return SharedOERContext(
                occurrence = OEROccurrence(result, emptyList(), result),
                source = engineObjectDataOf(queryType),
                closedDemand = selectionForestOf().merge(queryType),
            )
        }
    }
}
