package viaduct.engine.runtime2.resolution.framework

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection

/** One ordered namespace traversal shared by every maintained resolver family. */
internal class MutationOrchestrationTask<P> private constructor(
    private val operation: SharedOperationContext<SharedTaskDispatcher<*, *, P>>,
    private val namespace: MutationNamespaceOccurrence,
    private val prepareField: (OEROccurrence, ObjectSelection) -> P,
    private val namespacePrepared: (OEROccurrence) -> Unit,
) {
    private val dispatched = AtomicBoolean(false)
    private val publications = mutableMapOf<ObjectEngineResult.MutationKey, P>()
    private var child: MutationOrchestrationTask<P>? = null

    companion object {
        fun <P> create(
            operation: SharedOperationContext<SharedTaskDispatcher<*, *, P>>,
            namespace: MutationNamespaceOccurrence,
            prepareField: (OEROccurrence, ObjectSelection) -> P,
            namespacePrepared: (OEROccurrence) -> Unit = {},
        ): MutationOrchestrationTask<P> = MutationOrchestrationTask(operation, namespace, prepareField, namespacePrepared).apply { prepare() }
    }

    /** All local publications are claimed before any mutation is dispatched. */
    private fun prepare() {
        require(operation.world.resolverRegistry.typeChecker(namespace.target.type) == null) { "Mutation namespace type checkers are not supported" }
        for ((selection, key) in namespace.members) {
            require(operation.world.resolverRegistry.fieldChecker(key.field) == null) { "Mutation namespace field checkers are not supported" }
            if (selection.subselections is MutationSelectionForest) {
                namespace.target.getCell(key).value.claim()
            } else {
                val resolver = operation.world.resolverRegistry.resolver(key.field)
                require(resolver.objectFragment.isEmpty() && resolver.queryFragment.isEmpty()) {
                    "Mutation resolvers cannot declare object or Query fragments; use ctx.query()"
                }
                publications[key] = prepareField(namespace.occurrence, selection.publicationSelection(key))
            }
        }
        namespace.target.freeze()
        namespacePrepared(namespace.occurrence)
    }

    fun checkDispatch() {
        check(dispatched.compareAndSet(false, true)) { "Mutation orchestration dispatched twice" }
    }

    suspend fun run() {
        for ((selection, key) in namespace.members) {
            val cell = namespace.target.getCell(key)
            if (selection.subselections is MutationSelectionForest) {
                val nextNamespace = namespace.child(selection, key)
                val next = create(operation, nextNamespace, prepareField, namespacePrepared)
                child = next
                cell.setActivated(true)
                cell.fieldCheckerResult.complete(null)
                next.run()
                cell.value.complete(nextNamespace.target)
                child = null
            } else {
                operation.dispatcher.dispatchMutationField(publications.getValue(key), cell)
            }
        }
    }

    fun cancel(cause: CancellationException) {
        namespace.target.keys.forEach { key ->
            val cell = namespace.target.getCell(key)
            cell.value.cancel(cause)
            cell.fieldCheckerResult.cancel(cause)
        }
        child?.cancel(cause)
    }
}
