package semantics.resolvers.resolver23

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.GeneratedFieldCheckerContract
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
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
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    GeneratedFieldCheckerContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
