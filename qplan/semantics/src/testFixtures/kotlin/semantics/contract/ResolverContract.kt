package semantics.contract

import viaduct.engine.api.EngineObjectData
import model.Assumptions
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import viaduct.graphql.schema.ViaductSchema
import model.SelectionForest
import semantics.shared.SharedOperationContext
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.shared.CheckerObserver
import semantics.shared.ResolverObserver

/** Subject-specific evidence retained alongside one resolution result. */
interface ResolverResolutionObservation {
    val result: ObjectEngineResult
    val operation: SharedOperationContext<*>

    /** Exact resolver applications when the subject exposes occurrence-aware instrumentation. */
    val appliedResolverOccurrences: Set<ResolverOccurrenceId>?
        get() = null
}

private data class RecordedResolverResolutionObservation(
    override val result: ObjectEngineResult,
    override val operation: SharedOperationContext<*>,
    override val appliedResolverOccurrences: Set<ResolverOccurrenceId>?,
) : ResolverResolutionObservation

/**
 * A reusable contract subject for one field-resolution strategy.
 */
interface ResolverContract {
    val selectiveResolvers: Boolean
        get() = true

    fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult

    fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
        checkerObserver: CheckerObserver = CorrectnessCheckerObserver(),
    ): ResolverResolutionObservation {
        val correctnessResolverObserver =
            resolverObserver as? CorrectnessResolverObserver
                ?: CorrectnessResolverObserver(resolverObserver)
        val correctnessCheckerObserver =
            checkerObserver as? CorrectnessCheckerObserver
                ?: CorrectnessCheckerObserver(checkerObserver)
        return SharedOperationContext.create(
            world = world,
            resolverObserver = correctnessResolverObserver,
            checkerObserver = correctnessCheckerObserver,
        ).let { operation ->
            RecordedResolverResolutionObservation(
                result = resolve(operation, root, selections),
                operation = operation,
                appliedResolverOccurrences = correctnessResolverObserver.invokedResolverOccurrences(),
            )
        }
    }

    fun expectedPassiveResultFieldNames(vararg fieldNames: String): Set<String> =
        fieldNames.toSet()

    fun expectedPassiveResultKeys(
        @Suppress("UNUSED_PARAMETER")
        type: ViaductSchema.Object,
        keys: Set<ObjectEngineResult.GroundKey>,
    ): Set<ObjectEngineResult.GroundKey> = keys
}

internal fun EngineObjectData.Sync.hasExactlyFields(
    vararg expectedFields: ObjectEngineResult.GroundKey,
): Boolean = hasExactlyFields(expectedFields.toSet())

internal fun EngineObjectData.Sync.hasExactlyFields(
    expectedFields: Set<ObjectEngineResult.GroundKey>,
): Boolean =
    getSelections().toSet() ==
        expectedFields.mapTo(linkedSetOf()) { key -> key.field.name }
