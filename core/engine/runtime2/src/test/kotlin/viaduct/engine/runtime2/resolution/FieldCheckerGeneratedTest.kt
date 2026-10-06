package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.contract.GeneratedFieldCheckerContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Runtime checker profiles retain F4's independent replay and duplicate-preserving exactness. */
class FieldCheckerGeneratedTest :
    GeneratedFieldCheckerContract,
    ResolutionDispatcherResource {
    override val runtimeFieldCheckerVariables = true
    override val selectiveResolvers = true
    override val generatedResolverConfigOverrides = Config.default + (ResolverVariableSingletonCoercionEnabled to true)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
