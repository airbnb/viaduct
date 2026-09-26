package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.contract.CoroutineResolverContract
import semantics.shared.CycleCheckState
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext

class CoroutineResolveTest : CoroutineResolverContract {
    override val usesSingularQueryOER = true

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult {
        val resolverOperation = OperationContext.create(
            operation, requestScope, cycleChecker,
        )
        val source = operation.world.resolverRegistry.createRootQueryInput()
        val root = ObjectEngineResult.of(source.schemaType, mutable = true)
        resolverOperation.dispatcher.dispatchOrchestrator(
            OrchestrationTask.create(resolverOperation, OEROccurrence(root, emptyList(), root), source, selections),
        )
        return root
    }
}
