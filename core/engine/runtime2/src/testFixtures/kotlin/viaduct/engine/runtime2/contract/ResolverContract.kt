package viaduct.engine.runtime2.contract

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

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

    /**
     * Strategy hook invoked by [observeResolution] to run this subject's resolution algorithm.
     * Tests must call [observeResolution] instead of calling this hook directly, so shared observation setup is applied.
     */
    fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult

    fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    ): ResolverResolutionObservation {
        val correctnessResolverObserver =
            resolutionObserver as? CorrectnessResolverObserver
                ?: CorrectnessResolverObserver(resolutionObserver)
        return SharedOperationContext.create(
            world = world,
            resolutionObserver = correctnessResolverObserver,
        ).let { operation ->
            RecordedResolverResolutionObservation(
                result = resolve(operation, root, selections),
                operation = operation,
                appliedResolverOccurrences = correctnessResolverObserver.invokedResolverOccurrences(),
            )
        }
    }

    fun expectedPassiveResultFieldNames(vararg fieldNames: String): Set<String> = fieldNames.toSet()

    fun expectedPassiveResultKeys(
        @Suppress("UNUSED_PARAMETER")
        type: ViaductSchema.Object,
        keys: Set<ObjectEngineResult.GroundKey>,
    ): Set<ObjectEngineResult.GroundKey> = keys
}

fun EngineObjectData.Sync.hasExactlyFields(vararg expectedFields: ObjectEngineResult.GroundKey): Boolean = hasExactlyFields(expectedFields.toSet())

fun EngineObjectData.Sync.hasExactlyFields(expectedFields: Set<ObjectEngineResult.GroundKey>): Boolean =
    getSelections().toSet() ==
        expectedFields.mapTo(linkedSetOf()) { key -> key.field.name }
