package semantics.resolver26

import viaduct.engine.api.EngineObjectData

import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.FromProviderGeneratedResolverContract
import semantics.contract.GeneratedCaseAssertions
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
import semantics.contract.MixedVariableGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract
import semantics.contract.RootFieldReferenceGeneratedResolverContract
import semantics.contract.SelectiveNodeGeneratedResolverContract
import semantics.contract.SometimesPassiveGeneratedResolverContract
import semantics.shared.SharedOperationContext

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    RootFieldReferenceGeneratedResolverContract,
    SelectiveNodeGeneratedResolverContract,
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    ObjectFragmentFromObjectPathGeneratedResolverContract,
    FromProviderGeneratedResolverContract,
    MixedVariableGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract,
    Resolver26DispatcherResource {
    override val nodeRootFieldReferencesEnabled: Boolean
        get() = true

    override val queryFragmentObjectPathVariablesEnabled: Boolean
        get() = true

    override val queryFragmentQueryPathVariablesEnabled: Boolean
        get() = true

    override val generatedResolverConfigOverrides: Config =
        Config.default +
            (ResolverVariableSingletonCoercionEnabled to true)

    override val selectiveResolvers: Boolean
        get() = true

    override val generatedCaseAssertions =
        GeneratedCaseAssertions.defaultGeneratedContract +
            GeneratedCaseAssertions.exactOrdinaryApplicationCounts +
            GeneratedCaseAssertions.fromFieldBindings

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        operation.resolveWithTestDispatcher(selections)
}
