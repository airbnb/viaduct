package viaduct.engine.runtime2.contract

import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.correctresolution.conformsToSelections
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.correctresolution.isClosedUnderResolverDemand
import viaduct.engine.runtime2.correctresolution.rootedAndWellTyped
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.recording.RecordedResolutionSource
import viaduct.engine.runtime2.resolution.recording.ResolutionRecordings
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/**
 * Post-test policy requiring every contract result to satisfy the complete correctness judgment.
 */
interface CorrectResolutionPostTestPolicy : ResolverContract {
    @AfterEach
    fun validateContractResolutions() {
        ContractPostTestState.validateAndClear()
    }
}

private data class PendingResolutionValidation(
    val operation: SharedOperationContext<*>,
    val selections: SelectionForest,
    val result: ObjectEngineResult,
)

private object ContractPostTestState {
    private val pending =
        ThreadLocal.withInitial {
            mutableListOf<PendingResolutionValidation>()
        }

    fun record(validation: PendingResolutionValidation) {
        pending.get() += validation
    }

    fun validateAndClear() {
        val validations = pending.get().toList()
        pending.remove()
        validations.forEach { validation ->
            assertTrue(
                validation.result.correctResolution(
                    validation.operation,
                    validation.selections
                        .merge(validation.operation.world.schema.requireQueryTypeDef()),
                ),
                "rooted=${validation.result.rootedAndWellTyped(validation.operation.world)}, " +
                    "selections=" +
                    validation.result.conformsToSelections(
                        validation.operation,
                        validation.selections,
                    ) +
                    ", closed=" +
                    validation.result.isClosedUnderResolverDemand(validation.operation) +
                    ", resolvers=" +
                    validation.result.conformsToResolvers(validation.operation),
            )
        }
    }
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
    resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    record: Boolean = true,
): ObjectEngineResult = resolveAndValidateObserved(world, root, selections, resolutionObserver, record).result

internal fun ResolverContract.resolveAndValidateObserved(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
    resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    record: Boolean = true,
    source: RecordedResolutionSource? = null,
): ResolverResolutionObservation {
    if (record && source != null) ResolutionRecordings.setSource(this, source)
    val observation = observeResolution(world, root, selections, resolutionObserver, record)
    if (this is CorrectResolutionPostTestPolicy) {
        ContractPostTestState.record(
            PendingResolutionValidation(
                operation = observation.operation,
                selections = selections,
                result = observation.result,
            ),
        )
    }
    return observation
}

internal fun ResolverContract.resolveAndValidate(
    world: Assumptions,
    selections: SelectionForest,
    resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    record: Boolean = true,
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        root = world.objectOf("Query"),
        selections = selections,
        resolutionObserver = resolutionObserver,
        record = record,
    )

internal fun ResolverContract.resolveAndValidate(
    world: TestWorld,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    record: Boolean = true,
): ObjectEngineResult =
    resolveAndValidateObserved(
        world = world.assumptions,
        root = world.schema.objectOf("Query"),
        selections =
            world.schemas.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
        resolutionObserver = resolutionObserver,
        record = record,
        source = RecordedResolutionSource(world.schemaSDL, documentSource),
    ).result

internal fun ResolverContract.resolveAndValidateObserved(
    world: TestWorld,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolutionObserver: ResolutionObserver = CorrectnessResolverObserver(),
    record: Boolean = true,
): ResolverResolutionObservation =
    resolveAndValidateObserved(
        world = world.assumptions,
        root = world.schema.objectOf("Query"),
        selections =
            world.schemas.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
        resolutionObserver = resolutionObserver,
        record = record,
        source = RecordedResolutionSource(world.schemaSDL, documentSource),
    )
