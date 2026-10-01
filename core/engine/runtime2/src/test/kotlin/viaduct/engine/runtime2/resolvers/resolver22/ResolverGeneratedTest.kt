package viaduct.engine.runtime2.resolvers.resolver22

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.EmptyObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.FeatureInteractionGeneratedResolverContract
import viaduct.engine.runtime2.contract.NodeGeneratedResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceGeneratedResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveGeneratedResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    RootFieldReferenceGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
