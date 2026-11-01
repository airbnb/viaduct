package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult.ObjectKey
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.graphql.schema.ViaductSchema

/** The access-check result slot produced by one checker invocation. */
enum class CheckerKind {
    FIELD,
    TYPE,
}

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

/** Evidence connecting one symbolic reference hop to its independently rooted invocation. */
data class RootFieldReferenceInvocationObservation(
    val publicationRoot: ObjectEngineResult,
    val publicationPath: List<PathComponent>,
    val reference: RootFieldReferenceData,
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val invocationKey: ObjectKey,
    val suppliedDemand: SelectionForest,
)

/** Exact occurrence evidence captured immediately before a checker function is entered. */
data class CheckerInvocationObservation(
    val checkerKind: CheckerKind,
    val logicalQueryRoot: ObjectEngineResult,
    val occurrencePath: List<PathComponent>,
    val arguments: Arguments.Resolved?,
    val checkedTarget: ResolverTarget,
) {
    /** The checked field for a field-checker observation. */
    val checkedCoordinate: ViaductSchema.ObjectField
        get() = (checkedTarget as ResolverTarget.FieldCheckerTarget).field

    /** The checked concrete type for a type-checker observation. */
    val checkedType: ViaductSchema.Object
        get() = (checkedTarget as ResolverTarget.TypeCheckerTarget).type
}
