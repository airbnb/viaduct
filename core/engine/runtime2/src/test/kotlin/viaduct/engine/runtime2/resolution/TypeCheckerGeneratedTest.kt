package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.contract.GeneratedTypeCheckerContract
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Bounded mixed type/field profiles retain independent replay and exact occurrence accounting. */
class TypeCheckerGeneratedTest :
    GeneratedTypeCheckerContract,
    ResolutionDispatcherResource {
    override val typeCheckerProfilePrefix = "resolution"
    override val runtimeTypeCheckerVariables = true
    override val selectiveResolvers = true
    override val generatedResolverConfigOverrides = Config.default + (ResolverVariableSingletonCoercionEnabled to true)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
