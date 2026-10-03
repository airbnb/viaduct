package viaduct.engine.runtime2.resolution

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.currentCoroutineContext
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext

/**
 * Makes the active resolution capability available to production variables-provider adapters.
 *
 * The capability is scoped to the provider invocation's coroutine. It is deliberately absent from
 * resolver definitions, whose provider function shape remains independent of execution machinery.
 */
private class VariablesProviderExecutionContext(
    val resolutionContext: ResolutionExecutionContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VariablesProviderExecutionContext>
}

internal suspend fun <T> withVariablesProviderResolutionContext(
    resolutionContext: ResolutionExecutionContext,
    block: suspend () -> T,
): T =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        block.startCoroutineUninterceptedOrReturn(
            object : Continuation<T> {
                override val context = continuation.context + VariablesProviderExecutionContext(resolutionContext)

                override fun resumeWith(result: Result<T>) = continuation.resumeWith(result)
            },
        )
    }

internal suspend fun currentVariablesProviderResolutionContext(): ResolutionExecutionContext =
    currentCoroutineContext()[VariablesProviderExecutionContext]
        ?.resolutionContext
        ?: error("A production variables provider requires an active runtime2 resolution context")
