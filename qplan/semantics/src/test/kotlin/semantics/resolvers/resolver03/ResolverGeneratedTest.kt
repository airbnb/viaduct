package semantics.resolvers.resolver03

import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract
import semantics.contract.RootFieldReferenceGeneratedResolverContract
import semantics.contract.SometimesPassiveGeneratedResolverContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/**
 * Ordinary generated-world acceptance. Extended trace, mutation, depth, witness, and stress
 * claims remain in their dedicated suites or shared contracts.
 */
class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    RootFieldReferenceGeneratedResolverContract,
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
