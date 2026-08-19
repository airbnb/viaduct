@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.errors.TenantResolverException
import viaduct.java.api.internal.BaseUnbatchedFieldResolver
import viaduct.service.api.spi.GlobalIDCodec

class JavaFieldResolverExecutorTest {
    @Test
    fun `simple resolver returns expected value`(): Unit =
        runBlocking {
            // Wrap a simple resolve function in the bridge executor
            val executor = JavaFieldResolverExecutorImpl(
                resolver = fieldResolver { CompletableFuture.completedFuture("Hello, World!") },
                resolverId = "Query.greeting",
                resolverName = "GreetingResolver"
            )

            // Create mock selector and context
            val mockObjectValue = mockk<EngineObjectData.Sync>()
            val mockQueryValue = mockk<EngineObjectData.Sync>()
            val mockEngineContext = mockEngineContext()

            val selector = FieldResolverExecutor.Selector(
                arguments = emptyMap(),
                selections = null,
                syncObjectValueGetter = { mockObjectValue },
                syncQueryValueGetter = { mockQueryValue }
            )

            // Execute
            val results = executor.batchResolve(listOf(selector), mockEngineContext)

            // Verify
            assertEquals(1, results.size)
            val result = results[selector]
            assertNotNull(result)
            assertTrue(result!!.isSuccess)
            assertEquals("Hello, World!", result.getOrNull())
        }

    @Test
    fun `executor has correct metadata`() {
        val executor = JavaFieldResolverExecutorImpl(
            resolver = fieldResolver { CompletableFuture.completedFuture("test") },
            resolverId = "Query.greeting",
            resolverName = "GreetingResolver"
        )

        assertEquals("Query.greeting", executor.resolverId)
        assertEquals("GreetingResolver", executor.metadata.name)
        assertEquals("modern", executor.metadata.flavor)
        assertFalse(executor.isBatching)
        assertFalse(executor.isSelective)
        assertNull(executor.objectSelectionSet)
        assertNull(executor.querySelectionSet)
    }

    @Test
    fun `resolver that throws exception returns failure result`(): Unit =
        runBlocking {
            val failedFuture = CompletableFuture<Any?>()
            failedFuture.completeExceptionally(RuntimeException("Test error"))

            val executor = JavaFieldResolverExecutorImpl(
                resolver = fieldResolver { failedFuture },
                resolverId = "Query.failing",
                resolverName = "FailingResolver"
            )

            val mockObjectValue = mockk<EngineObjectData.Sync>()
            val mockQueryValue = mockk<EngineObjectData.Sync>()
            val mockEngineContext = mockEngineContext()

            val selector = FieldResolverExecutor.Selector(
                arguments = emptyMap(),
                selections = null,
                syncObjectValueGetter = { mockObjectValue },
                syncQueryValueGetter = { mockQueryValue }
            )

            val results = executor.batchResolve(listOf(selector), mockEngineContext)

            assertEquals(1, results.size)
            val result = results[selector]
            assertNotNull(result)
            assertTrue(result!!.isFailure)
            val ex = result.exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
            ex.cause.shouldBeInstanceOf<RuntimeException>()
            assertEquals("Test error", generateSequence(ex.cause) { it.cause }.last().message)
        }

    @Test
    fun `resolver cancellation propagates instead of becoming failure result`() {
        val blockedFuture = CompletableFuture<Any?>()

        val executor = JavaFieldResolverExecutorImpl(
            resolver = fieldResolver { blockedFuture },
            resolverId = "Query.cancelled",
            resolverName = "CancelledResolver"
        )

        val mockObjectValue = mockk<EngineObjectData.Sync>()
        val mockQueryValue = mockk<EngineObjectData.Sync>()
        val mockEngineContext = mockEngineContext()

        val selector = FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { mockObjectValue },
            syncQueryValueGetter = { mockQueryValue }
        )

        assertThrows<CancellationException> {
            runBlocking {
                withTimeout(50) {
                    executor.batchResolve(listOf(selector), mockEngineContext)
                }
            }
        }
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

        val executor = JavaFieldResolverExecutorImpl(
            resolver = fieldResolver { CompletableFuture.completedFuture("test") },
            resolverId = "Person.fullName",
            resolverName = "FullNameResolver",
            objectSelectionSet = requiredSelectionSet
        )

        assertNotNull(executor.objectSelectionSet)
        assertEquals(requiredSelectionSet, executor.objectSelectionSet)
        assertNull(executor.querySelectionSet)
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

        val executor = JavaFieldResolverExecutorImpl(
            resolver = fieldResolver { CompletableFuture.completedFuture("test") },
            resolverId = "Person.greeting",
            resolverName = "GreetingResolver",
            querySelectionSet = requiredSelectionSet
        )

        assertNotNull(executor.querySelectionSet)
        assertEquals(requiredSelectionSet, executor.querySelectionSet)
        assertNull(executor.objectSelectionSet)
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

        val executor = JavaFieldResolverExecutorImpl(
            resolver = fieldResolver { CompletableFuture.completedFuture("test") },
            resolverId = "Person.computed",
            resolverName = "ComputedResolver",
            objectSelectionSet = objectSelectionSet,
            querySelectionSet = querySelectionSet
        )

        assertNotNull(executor.objectSelectionSet)
        assertEquals(objectSelectionSet, executor.objectSelectionSet)
        assertNotNull(executor.querySelectionSet)
        assertEquals(querySelectionSet, executor.querySelectionSet)
    }

    private fun fieldResolver(resolve: () -> CompletableFuture<*>): Provider<BaseUnbatchedFieldResolver> =
        Provider {
            BaseUnbatchedFieldResolver { resolve() }
        }

    private fun mockEngineContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
            every { fullSchema } returns mockk<ViaductSchema>()
            every { globalIDCodec } returns mockk<GlobalIDCodec>()
        }
}
