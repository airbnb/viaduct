package viaduct.engine.runtime2.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.contract.CoroutineResolverContract
import viaduct.engine.runtime2.contract.CoroutineResolverTestSubject
import viaduct.engine.runtime2.contract.FragmentFreeCheckerProfileContract
import viaduct.engine.runtime2.contract.FragmentFreeFieldCheckerPublicationContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class CoroutineResolveTest :
    CoroutineResolverTestSubject(),
    CoroutineResolverContract,
    FragmentFreeCheckerProfileContract,
    FragmentFreeFieldCheckerPublicationContract {
    override val selectiveResolvers = false
    override val usesSingularQueryOER = true

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult = startCoroutineResolution(operation, requestScope, selections, cycleChecker)
}

internal fun startCoroutineResolution(
    operation: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    selections: SelectionForest,
    cycleChecker: CycleCheckState,
    complete: (Demand<SelectionForest>, Set<viaduct.graphql.schema.ViaductSchema.Object>) -> SelectionForest =
        { demand, _ -> demand.values },
): ObjectEngineResult =
    CoroutineOperationContext(
        operation,
        requestScope,
        complete,
        cycleChecker,
    ).startResolve(
        source = operation.world.resolverRegistry.createRootQueryInput(),
        demand = viaduct.engine.runtime2.resolution.framework.Demand.checked(selections),
    )
