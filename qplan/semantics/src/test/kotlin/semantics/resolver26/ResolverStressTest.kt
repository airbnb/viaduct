package semantics.resolver26

import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.contract.DeepResolverStressContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ResolverStressTest : DeepResolverStressContract, Resolver26DispatcherResource {
    override val resolverName: String = "resolver26"

    override val objectPathVariablesEnabled: Boolean = true

    override val queryPathVariablesEnabled: Boolean = true

    override val sometimesPassiveCoverageRequired: Boolean = true

    override val rootFieldReferenceCoverageRequired: Boolean = true

    override val stressConfigOverrides: Config =
        Config.default +
            (ResolverVariableSingletonCoercionEnabled to true) +
            (ParentFieldsEnabled to true) +
            (RootFieldReferencesEnabled to true) +
            (RootFieldReferenceWeight to 0.2) +
            (MaxSelectionDepth to 6) +
            (SometimesPassiveFieldWeight to 0.25)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
