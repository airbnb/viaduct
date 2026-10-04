package viaduct.engine.runtime2.resolution

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext

class VariablesProviderExecutionContextTest {
    @Test
    fun `provider context is scoped to the invocation and survives suspension`() =
        runTest {
            val invocationJob = currentCoroutineContext()[Job]

            withVariablesProviderResolutionContext(resolutionContext) {
                assertSame(invocationJob, currentCoroutineContext()[Job])
                assertSame(resolutionContext, currentVariablesProviderResolutionContext())
                yield()
                withContext(Dispatchers.Default) {
                    assertSame(resolutionContext, currentVariablesProviderResolutionContext())
                }
            }

            val failure = runCatching { currentVariablesProviderResolutionContext() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }

    @Test
    fun `provider context preserves failure and cancellation identity`() =
        runTest {
            val failure = IllegalStateException("failure")
            val caughtFailure =
                runCatching {
                    withVariablesProviderResolutionContext(resolutionContext) {
                        throw failure
                    }
                }.exceptionOrNull()
            assertSame(failure, caughtFailure)

            val cancellation = CancellationException("cancelled")
            val caughtCancellation =
                runCatching {
                    withVariablesProviderResolutionContext(resolutionContext) {
                        throw cancellation
                    }
                }.exceptionOrNull()
            assertSame(cancellation, caughtCancellation)
        }

    private val resolutionContext =
        object : ResolutionExecutionContext {
            override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync = error("Selection execution is not used by this test")
        }
}
