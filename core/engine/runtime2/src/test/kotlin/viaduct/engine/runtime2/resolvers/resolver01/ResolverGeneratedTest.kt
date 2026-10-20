package viaduct.engine.runtime2.resolvers.resolver01

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.EmptyObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.NodeGeneratedResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceGeneratedResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveGeneratedResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

open class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    RootFieldReferenceGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}

class PrecomputedResolverGeneratedTest : ResolverGeneratedTest() {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        SharedOperationContext.create(
            world =
                Assumptions.of(
                    schema = operation.world.schema,
                    resolverRegistry = operation.world.resolverRegistry,
                    selectiveResolvers = operation.world.selectiveResolvers,
                    usePrecomputedConstructionDemand = true,
                ),
            variableBindings = operation.variableBindings,
            resolverObserver = operation.resolverObserver,
            checkerObserver = operation.checkerObserver,
            engineExecutionContext = operation.engineExecutionContext,
        ).resolve(selections)
}
