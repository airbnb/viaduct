@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.service.api.spi.FlagManager

class NextTickDispatcherMetricsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `counts non-empty rounds once and includes empty flushes`(killSwitch: Boolean): Unit =
        runTest {
            val flags = object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = killSwitch && flag == FlagManager.Flags.KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH
            }
            val dispatcher = NextTickDispatcher(StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler), flagManager = flags, metricsEnabled = true)
            val callbacks = mutableListOf<Int>()
            assertEquals(0L, dispatcher.flushCount)
            assertEquals(0L, dispatcher.batchRoundCount)

            CoroutineScope(dispatcher).launch {
                dispatcher.nextTick(coroutineContext) { callbacks.add(1) }
                dispatcher.nextTick(coroutineContext) { callbacks.add(2) }
            }
            advanceUntilIdle()

            assertEquals(listOf(1, 2), callbacks)
            assertEquals(1L, dispatcher.flushCount)
            assertEquals(1L, dispatcher.batchRoundCount)

            CoroutineScope(dispatcher).launch {}
            advanceUntilIdle()

            assertEquals(2L, dispatcher.flushCount)
            assertEquals(1L, dispatcher.batchRoundCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `metrics are disabled by default without changing callback execution`(killSwitch: Boolean): Unit =
        runTest {
            val flags = object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = killSwitch && flag == FlagManager.Flags.KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH
            }
            val dispatcher = NextTickDispatcher(StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler), flagManager = flags)
            val callbacks = mutableListOf<Int>()

            CoroutineScope(dispatcher).launch {
                dispatcher.nextTick(coroutineContext) { callbacks.add(1) }
                dispatcher.nextTick(coroutineContext) { callbacks.add(2) }
            }
            advanceUntilIdle()
            CoroutineScope(dispatcher).launch {}
            advanceUntilIdle()

            assertEquals(listOf(1, 2), callbacks)
            assertEquals(0L, dispatcher.flushCount)
            assertEquals(0L, dispatcher.batchRoundCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `throwing runnable only counts its empty flush when enabled`(metricsEnabled: Boolean): Unit =
        runTest {
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable
                ) = block.run()
            }
            val dispatcher = NextTickDispatcher(worker, StandardTestDispatcher(testScheduler), metricsEnabled = metricsEnabled)
            val failure = IllegalStateException("runnable failed")

            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    dispatcher.dispatch(EmptyCoroutineContext, Runnable { throw failure })
                }
            )
            advanceUntilIdle()

            assertEquals(if (metricsEnabled) 1L else 0L, dispatcher.flushCount)
            assertEquals(0L, dispatcher.batchRoundCount)
        }
}
