@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver06

import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTask

/** Synchronous driver/queue entry for the shared mutation orchestration task. */
class DepthFirstMutationTask(
    operation: DepthFirstOperationContext,
    namespace: MutationNamespaceOccurrence,
    creator: ResolutionTask? = null,
) : DepthFirstTask {
    override val path = namespace.occurrence.path
    override val queryOERDepth = 0
    private val orchestration = MutationOrchestrationTask.create(
        operation = operation,
        namespace = namespace,
        creator = creator,
        prepareField = { task, occurrence, selection -> DepthFirstFieldResolverTask.prepareMutation(operation, occurrence, selection, task) },
        namespacePrepared = operation.dispatcher::mutationNamespacePrepared,
    )

    fun run() {
        orchestration.checkDispatch()
        runBlocking { orchestration.run() }
    }
}
