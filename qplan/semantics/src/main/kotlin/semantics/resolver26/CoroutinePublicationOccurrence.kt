package semantics.resolver26

import kotlinx.coroutines.CoroutineScope

/** A publication that owns its request-root coroutine launch and cancellation boundary. */
internal interface CoroutinePublicationOccurrence {
    fun dispatch(requestScope: CoroutineScope)
}
