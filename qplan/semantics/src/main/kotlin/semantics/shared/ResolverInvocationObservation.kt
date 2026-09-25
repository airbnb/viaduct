package semantics.shared

import model.Arguments
import model.MaterializeSelectionForest
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Facts captured immediately before one ordinary or reference-target resolver invocation. Both
 * response-keyed materialized inputs are retained so correctness replay can validate access errors
 * at their exact locations. Records attempts, including invocations that subsequently throw or are
 * cancelled; no output is recorded. A null [suppliedDemand] denotes complete, nonselective execution.
 */
data class ResolverInvocationObservation(
    val occurrencePath: List<PathComponent>,
    val field: ViaductSchema.ObjectField,
    val input: EngineObjectData.Sync,
    val inputSelections: MaterializeSelectionForest,
    val queryValue: EngineObjectData.Sync,
    val queryInputSelections: MaterializeSelectionForest,
    val arguments: Arguments.Resolved,
    val suppliedDemand: SelectionForest?,
    val resolverOccurrenceId: ResolverOccurrenceId,
)
