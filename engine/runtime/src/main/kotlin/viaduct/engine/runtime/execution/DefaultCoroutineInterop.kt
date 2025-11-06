package viaduct.engine.runtime.execution

import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.future.future
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.execution.scopedAsync as oss_scopedAsync
import viaduct.engine.runtime.execution.scopedFuture as oss_scopedFuture
import viaduct.engine.runtime.execution.withThreadLocalCoroutineContext as oss_withThreadLocalCoroutineContext

object DefaultCoroutineInterop : CoroutineInterop {
    override fun <T> scopedFuture(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = oss_scopedFuture(block)

    override fun <T> enterThreadLocalCoroutineContext(
        callerContext: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): CompletableFuture<T> =
        CoroutineScope(callerContext + NextTickDispatcher()).future {
            oss_withThreadLocalCoroutineContext {
                block()
            }
        }

    override fun <T> scopedAsync(
        context: CoroutineContext,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = oss_scopedAsync(context, start, block)
}
