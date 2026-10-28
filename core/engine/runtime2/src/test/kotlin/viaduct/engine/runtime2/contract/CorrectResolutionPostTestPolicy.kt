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
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
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
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ObjectEngineResult = resolveAndValidateObserved(world, root, selections, resolverObserver).result

internal fun ResolverContract.resolveAndValidateObserved(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ResolverResolutionObservation {
    val observation = observeResolution(world, root, selections, resolverObserver)
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
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        root = world.objectOf("Query"),
        selections = selections,
        resolverObserver = resolverObserver,
    )

internal fun ResolverContract.resolveAndValidate(
    world: TestWorld,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ObjectEngineResult =
    resolveAndValidate(
        world = world.assumptions,
        selections =
            world.schemas.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
        resolverObserver = resolverObserver,
    )

internal fun ResolverContract.resolveAndValidateObserved(
    world: TestWorld,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
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
        resolverObserver = resolverObserver,
    )
