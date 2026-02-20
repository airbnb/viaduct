@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldResolverExecutor

class JavaFieldResolverExecutorTest {
    @Test
    fun `simple resolver returns expected value`(): Unit =
        runBlocking {
            // Wrap a simple resolve function in the bridge executor
            val executor = JavaFieldResolverExecutor(
                resolveFunction = { CompletableFuture.completedFuture("Hello, World!") },
                resolverId = "Query.greeting",
                resolverName = "GreetingResolver"
            )

            // Create mock selector and context
            val mockObjectValue = mockk<EngineObjectData>()
            val mockQueryValue = mockk<EngineObjectData>()
            val mockEngineContext = mockk<EngineExecutionContext> {
                every { requestContext } returns null
            }

            val selector = FieldResolverExecutor.Selector(
                arguments = emptyMap(),
                objectValue = mockObjectValue,
                queryValue = mockQueryValue,
                selections = null
            )

            // Execute
            val results = executor.batchResolve(listOf(selector), mockEngineContext)

            // Verify
            assertThat(results).hasSize(1)
            val result = results[selector]
            assertThat(result).isNotNull
            assertThat(result!!.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("Hello, World!")
        }

    @Test
    fun `executor has correct metadata`() {
        val executor = JavaFieldResolverExecutor(
            resolveFunction = { CompletableFuture.completedFuture("test") },
            resolverId = "Query.greeting",
            resolverName = "GreetingResolver"
        )

        assertThat(executor.resolverId).isEqualTo("Query.greeting")
        assertThat(executor.metadata.name).isEqualTo("GreetingResolver")
        assertThat(executor.metadata.flavor).isEqualTo("modern")
        assertThat(executor.isBatching).isFalse()
        assertThat(executor.objectSelectionSet).isNull()
        assertThat(executor.querySelectionSet).isNull()
    }

    @Test
    fun `resolver that throws exception returns failure result`(): Unit =
        runBlocking {
            val failedFuture = CompletableFuture<Any?>()
            failedFuture.completeExceptionally(RuntimeException("Test error"))

            val executor = JavaFieldResolverExecutor(
                resolveFunction = { failedFuture },
                resolverId = "Query.failing",
                resolverName = "FailingResolver"
            )

            val mockObjectValue = mockk<EngineObjectData>()
            val mockQueryValue = mockk<EngineObjectData>()
            val mockEngineContext = mockk<EngineExecutionContext> {
                every { requestContext } returns null
            }

            val selector = FieldResolverExecutor.Selector(
                arguments = emptyMap(),
                objectValue = mockObjectValue,
                queryValue = mockQueryValue,
                selections = null
            )

            val results = executor.batchResolve(listOf(selector), mockEngineContext)

            assertThat(results).hasSize(1)
            val result = results[selector]
            assertThat(result).isNotNull
            assertThat(result!!.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(RuntimeException::class.java)
            assertThat(result.exceptionOrNull()?.message).isEqualTo("Test error")
        }
}
