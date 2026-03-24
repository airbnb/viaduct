package viaduct.dataloader.mocks

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import viaduct.dataloader.NextTickDispatcher
import viaduct.service.api.spi.FlagManager

/**
 * This class is a combination of [TestCoroutineDispatcher] and [NextTickDispatcher]
 *
 * It is meant to be used in unit tests only.
 *
 * IMPORTANT: Do NOT use this in PROD code as the implementation
 * of [TestCoroutineDispatcher] is using a virtual clock (to speed up tests).
 */
@OptIn(InternalCoroutinesApi::class)
@ExperimentalCoroutinesApi
class MockNextTickDispatcher constructor(
    private val testScheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
    private val internalDispatcher: TestDispatcher = StandardTestDispatcher(testScheduler),
    private val batchQueueDispatcher: TestDispatcher = UnconfinedTestDispatcher(testScheduler),
) : Delay, NextTickDispatcher(internalDispatcher, batchQueueDispatcher, flagManager = FlagManager.disabled) {
    @InternalCoroutinesApi
    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        internalDispatcher.scheduleResumeAfterDelay(timeMillis, continuation)
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext
    ): DisposableHandle {
        return internalDispatcher.invokeOnTimeout(timeMillis, block, context)
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return internalDispatcher.isDispatchNeeded(context)
    }
}
