package viaduct.engine.runtime2.resolvers.resolver06

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.EmptyObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.NodeGeneratedResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceGeneratedResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveGeneratedResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverGeneratedTest :
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
