package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.contract.EmptyObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.FeatureInteractionGeneratedResolverContract
import viaduct.engine.runtime2.contract.FromProviderGeneratedResolverContract
import viaduct.engine.runtime2.contract.GeneratedCaseAssertions
import viaduct.engine.runtime2.contract.ListPassiveDeepeningGeneratedResolverContract
import viaduct.engine.runtime2.contract.MixedVariableGeneratedResolverContract
import viaduct.engine.runtime2.contract.NodeGeneratedResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import viaduct.engine.runtime2.contract.ObjectFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.QueryFragmentGeneratedResolverContract
import viaduct.engine.runtime2.contract.RootFieldReferenceGeneratedResolverContract
import viaduct.engine.runtime2.contract.SelectiveNodeGeneratedResolverContract
import viaduct.engine.runtime2.contract.SometimesPassiveGeneratedResolverContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
    ResolutionDispatcherResource {
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
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
