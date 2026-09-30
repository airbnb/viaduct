package semantics.resolver26

import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.contract.GeneratedTypeCheckerContract
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/** Bounded mixed type/field profiles retain independent replay and exact occurrence accounting. */
class TypeCheckerGeneratedTest :
    GeneratedTypeCheckerContract,
    Resolver26DispatcherResource {
    override val typeCheckerProfilePrefix = "resolver26"
    override val runtimeTypeCheckerVariables = true
    override val selectiveResolvers = true
    override val generatedResolverConfigOverrides = Config.default + (ResolverVariableSingletonCoercionEnabled to true)

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)
}
