package semantics.contract

import model.requireQueryTypeDef
import viaduct.engine.api.EngineObjectData
import model.Assumptions
import model.ObjectEngineResult
import model.SelectionForest
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import org.junit.jupiter.api.AfterEach
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.assertTrue
import semantics.shared.ResolverObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.SharedOperationContext

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
    world: Assumptions,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ObjectEngineResult =
    resolveAndValidate(
        world = world,
        selections =
            world.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
        resolverObserver = resolverObserver,
    )

internal fun ResolverContract.resolveAndValidateObserved(
    world: Assumptions,
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    resolverObserver: ResolverObserver = CorrectnessResolverObserver(),
): ResolverResolutionObservation =
    resolveAndValidateObserved(
        world = world,
        root = world.objectOf("Query"),
        selections =
            world.operationSelectionsFrom(
                documentSource = documentSource,
                variables = variables,
                operationName = operationName,
            ),
        resolverObserver = resolverObserver,
    )
