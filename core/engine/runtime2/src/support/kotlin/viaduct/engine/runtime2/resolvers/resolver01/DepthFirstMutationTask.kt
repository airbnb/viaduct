@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask

/** Synchronous driver/queue entry for the shared mutation orchestration task. */
internal class DepthFirstMutationTask(
    operation: DepthFirstOperationContext,
    namespace: MutationNamespaceOccurrence,
) : DepthFirstTask {
    override val path = namespace.occurrence.path
    override val queryOERDepth = 0
    private val orchestration = MutationOrchestrationTask.create(
        operation = operation,
        namespace = namespace,
        prepareField = { occurrence, selection -> DepthFirstFieldResolverTask.prepareMutation(operation, occurrence, selection) },
        namespacePrepared = operation.dispatcher::mutationNamespacePrepared,
    )

    fun run() {
        orchestration.checkDispatch()
        runBlocking { orchestration.run() }
    }
}
