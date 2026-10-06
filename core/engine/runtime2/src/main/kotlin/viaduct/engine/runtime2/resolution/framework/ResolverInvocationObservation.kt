package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
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
