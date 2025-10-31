package viaduct.engine.runtime.execution

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.future.future
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.coroutines.CoroutineInterop

object DefaultCoroutineInterop : CoroutineInterop {
    override fun <T> scopedFuture(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = viaduct.engine.runtime.execution.scopedFuture(block)

    override fun <T> enterThreadLocalCoroutineContext(
        callerContext: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): CompletableFuture<T> =
        CoroutineScope(callerContext + NextTickDispatcher()).future {
            withThreadLocalCoroutineContext {
                block()
            }
        }

    override fun <T> scopedAsync(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = viaduct.engine.runtime.execution.scopedAsync(context, start, block)
}
