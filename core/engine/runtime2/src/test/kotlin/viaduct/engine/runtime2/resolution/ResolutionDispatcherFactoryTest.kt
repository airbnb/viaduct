@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ExecutorService
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ResolutionDispatcherFactoryTest {
    @Test
    fun `create returns fresh caller-owned daemon pools with distinguishable names`() {
        ResolutionDispatcherFactory.create(1).use { first ->
            ResolutionDispatcherFactory.create(1).use { second ->
                assertNotSame(first, second)
                val firstThread = runBlocking { withContext(first) { Thread.currentThread() } }
                val secondThread = runBlocking { withContext(second) { Thread.currentThread() } }

                assertTrue(firstThread.isDaemon)
                assertTrue(secondThread.isDaemon)
                assertTrue(firstThread.name.matches(Regex("resolution-\\d+-1")))
                assertTrue(secondThread.name.matches(Regex("resolution-\\d+-1")))
                assertNotEquals(firstThread.name, secondThread.name)
            }
        }
    }

    @Test
    fun `create requires a positive thread count`() {
        assertFailsWith<IllegalArgumentException> {
            ResolutionDispatcherFactory.create(0)
        }
    }

    @Test
    fun `closing the dispatcher shuts down its executor`() {
        val dispatcher = ResolutionDispatcherFactory.create(1)
        val executor = dispatcher.executor as ExecutorService

        dispatcher.close()

        assertTrue(executor.isShutdown)
    }

    @Test
    fun `configured thread count reads the existing system property and validates it`() {
        val previous = System.getProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION)
        try {
            System.setProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION, "7")
            assertTrue(configuredResolutionThreadCount() == 7)

            System.setProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION, "0")
            assertFailsWith<IllegalStateException> {
                configuredResolutionThreadCount()
            }
        } finally {
            if (previous == null) {
                System.clearProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION)
            } else {
                System.setProperty(RESOLUTION_THREAD_COUNT_CONFIGURATION, previous)
            }
        }
    }
}
