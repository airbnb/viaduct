package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.resolution.framework.ResolutionTask

/** A publication that owns its request-root coroutine launch and cancellation boundary. */
internal interface CoroutinePublicationOccurrence {
    val task: ResolutionTask

    fun dispatch(requestScope: CoroutineScope)
}
