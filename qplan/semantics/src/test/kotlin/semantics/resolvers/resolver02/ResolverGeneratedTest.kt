package semantics.resolvers.resolver02

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract
import semantics.contract.RootFieldReferenceGeneratedResolverContract
import semantics.contract.SometimesPassiveGeneratedResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

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
