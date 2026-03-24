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
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor

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

    @Test
    fun `executor with objectSelectionSet has correct value`() {
        val objectSelections = SelectionsParser.parse("Person", "name age")
        val requiredSelectionSet = RequiredSelectionSet(
            objectSelections,
            emptyList(),
            forChecker = false,
            ExecutionAttribution.fromResolver("TestResolver")
        )

        val executor = JavaFieldResolverExecutor(
            resolveFunction = { CompletableFuture.completedFuture("test") },
            resolverId = "Person.fullName",
            resolverName = "FullNameResolver",
            objectSelectionSet = requiredSelectionSet
        )

        assertThat(executor.objectSelectionSet).isNotNull
        assertThat(executor.objectSelectionSet).isEqualTo(requiredSelectionSet)
        assertThat(executor.querySelectionSet).isNull()
    }

    @Test
    fun `executor with querySelectionSet has correct value`() {
        val querySelections = SelectionsParser.parse("Query", "currentUser { id }")
        val requiredSelectionSet = RequiredSelectionSet(
            querySelections,
            emptyList(),
            forChecker = false,
            ExecutionAttribution.fromResolver("TestResolver")
        )

        val executor = JavaFieldResolverExecutor(
            resolveFunction = { CompletableFuture.completedFuture("test") },
            resolverId = "Person.greeting",
            resolverName = "GreetingResolver",
            querySelectionSet = requiredSelectionSet
        )

        assertThat(executor.querySelectionSet).isNotNull
        assertThat(executor.querySelectionSet).isEqualTo(requiredSelectionSet)
        assertThat(executor.objectSelectionSet).isNull()
    }

    @Test
    fun `executor with both selection sets has correct values`() {
        val objectSelections = SelectionsParser.parse("Person", "name")
        val querySelections = SelectionsParser.parse("Query", "config { setting }")
        val attribution = ExecutionAttribution.fromResolver("DualResolver")

        val objectSelectionSet = RequiredSelectionSet(
            objectSelections,
            emptyList(),
            forChecker = false,
            attribution
        )
        val querySelectionSet = RequiredSelectionSet(
            querySelections,
            emptyList(),
            forChecker = false,
            attribution
        )

        val executor = JavaFieldResolverExecutor(
            resolveFunction = { CompletableFuture.completedFuture("test") },
            resolverId = "Person.computed",
            resolverName = "ComputedResolver",
            objectSelectionSet = objectSelectionSet,
            querySelectionSet = querySelectionSet
        )

        assertThat(executor.objectSelectionSet).isNotNull
        assertThat(executor.objectSelectionSet).isEqualTo(objectSelectionSet)
        assertThat(executor.querySelectionSet).isNotNull
        assertThat(executor.querySelectionSet).isEqualTo(querySelectionSet)
    }
}
