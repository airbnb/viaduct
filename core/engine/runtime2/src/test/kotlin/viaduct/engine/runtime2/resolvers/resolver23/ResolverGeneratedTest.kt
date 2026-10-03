package viaduct.engine.runtime2.resolvers.resolver23

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.EmptyObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.FeatureInteractionGeneratedResolverContract
import viaduct.engine.runtime2.contract.GeneratedFieldCheckerContract
import viaduct.engine.runtime2.contract.GeneratedTypeCheckerContract
import viaduct.engine.runtime2.contract.ListPassiveDeepeningGeneratedResolverContract
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
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    GeneratedFieldCheckerContract,
    GeneratedTypeCheckerContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolve(selections)
}
