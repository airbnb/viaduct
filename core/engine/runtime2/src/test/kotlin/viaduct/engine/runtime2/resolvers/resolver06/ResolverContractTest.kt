package viaduct.engine.runtime2.resolvers.resolver06

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CompleteOutputRootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.CompleteResolverOutputPolicyContract
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.DepthFirstRootFieldReferenceOrderingContract
import viaduct.engine.runtime2.contract.DepthFirstTaskOrderingContract
import viaduct.engine.runtime2.contract.EmptyObjectFragmentResolverContract
import viaduct.engine.runtime2.contract.FrozenObjectResolutionContract
import viaduct.engine.runtime2.contract.NodeResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentVariablesResolverContract
import viaduct.engine.runtime2.contract.ResolverResolutionObservation
import viaduct.engine.runtime2.contract.ResolverTaskObservation
import viaduct.engine.runtime2.contract.RootFieldReferenceResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.resolution.framework.CheckerObserver
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

open class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    QueryFragmentVariablesResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    FrozenObjectResolutionContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)

    override fun resolveAndObserveTasks(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult =
        operation.resolve(
            selections = selections,
            onTaskStarted = { task ->
                taskObserver(task.toContractObservation())
            },
        )
}

class PrecomputedResolverContractTest : ResolverContractTest() {
    override fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        resolverObserver: ResolverObserver,
        checkerObserver: CheckerObserver,
    ): ResolverResolutionObservation =
        super.observeResolution(
            world =
                Assumptions.of(
                    schema = world.schema,
                    resolverRegistry = world.resolverRegistry,
                    selectiveResolvers = world.selectiveResolvers,
                    usePrecomputedConstructionDemand = true,
                ),
            root = root,
            selections = selections,
            resolverObserver = resolverObserver,
            checkerObserver = checkerObserver,
        )
}
