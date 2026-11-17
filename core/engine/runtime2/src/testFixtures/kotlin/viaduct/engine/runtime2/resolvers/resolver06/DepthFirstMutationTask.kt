@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers.resolver06

import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask

/** Synchronous driver/queue entry for the shared mutation orchestration task. */
class DepthFirstMutationTask internal constructor(
    private val orchestration: MutationOrchestrationTask<*>,
) : DepthFirstTask {
    override val path = orchestration.occurrence.path
    override val queryOERDepth = 0

    fun run() {
        orchestration.checkDispatch()
        runBlocking { orchestration.run() }
    }
}
