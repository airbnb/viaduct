@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.deferred

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThreadLocalCoroutineContextManagerTest {
    @Nested
    inner class RequestParentJobContextElementTests {
        @Test
        fun `currentRequestParentJobOrNull returns null when no context is installed`() {
            // Clear any leftover thread-local state
            RequestParentJobContextElement.tlRequestParentJob.set(null)
            assertNull(currentRequestParentJobOrNull())
        }

        @Test
        fun `currentRequestParentJobOrNull returns request job inside context`() =
            runTest {
                val requestJob = Job()

                withContext(
                    ThreadLocalCoroutineContextManager.ContextElement(defaultJob = requestJob) +
                        RequestParentJobContextElement(requestJob)
                ) {
                    assertSame(requestJob, currentRequestParentJobOrNull())
                }
            }

        @Test
        fun `thread-local is restored to null after exiting request context`() =
            runTest {
                val requestJob = Job()

                withContext(
                    ThreadLocalCoroutineContextManager.ContextElement(defaultJob = requestJob) +
                        RequestParentJobContextElement(requestJob)
                ) {
                    assertSame(requestJob, currentRequestParentJobOrNull())
                }

                // After exiting, thread-local should be restored
                assertNull(RequestParentJobContextElement.tlRequestParentJob.get())
            }

        @Test
        fun `nested request contexts restore correctly`() =
            runTest {
                val outerJob = Job()
                val innerJob = Job()

                withContext(
                    ThreadLocalCoroutineContextManager.ContextElement(defaultJob = outerJob) +
                        RequestParentJobContextElement(outerJob)
                ) {
                    assertSame(outerJob, currentRequestParentJobOrNull())

                    withContext(RequestParentJobContextElement(innerJob)) {
                        assertSame(innerJob, currentRequestParentJobOrNull())
                    }

                    // Outer should be restored after inner exits
                    assertSame(outerJob, currentRequestParentJobOrNull())
                }
            }

        @Test
        fun `thread-local is consistent across coroutine suspension points`() =
            runTest {
                val requestJob = Job()

                withContext(
                    ThreadLocalCoroutineContextManager.ContextElement(defaultJob = requestJob) +
                        RequestParentJobContextElement(requestJob)
                ) {
                    assertSame(requestJob, currentRequestParentJobOrNull())
                    yield()
                    assertSame(requestJob, currentRequestParentJobOrNull())
                    yield()
                    assertSame(requestJob, currentRequestParentJobOrNull())
                }
            }
    }
}
