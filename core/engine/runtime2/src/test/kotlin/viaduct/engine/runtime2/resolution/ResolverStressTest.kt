package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.MaxSelectionDepth
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceWeight
import viaduct.engine.runtime2.arbitrary.RootFieldReferencesEnabled
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.contract.DeepResolverStressContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverStressTest : DeepResolverStressContract, ResolutionDispatcherResource {
    override val resolverName: String = "resolution"

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
