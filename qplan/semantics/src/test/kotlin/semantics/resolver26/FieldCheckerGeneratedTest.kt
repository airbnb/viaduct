package semantics.resolver26

import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.contract.GeneratedFieldCheckerContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/** Runtime checker profiles retain F4's independent replay and duplicate-preserving exactness. */
class FieldCheckerGeneratedTest :
    GeneratedFieldCheckerContract,
    Resolver26DispatcherResource {
    override val runtimeFieldCheckerVariables = true
    override val selectiveResolvers = true
    override val generatedResolverConfigOverrides = Config.default + (ResolverVariableSingletonCoercionEnabled to true)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
