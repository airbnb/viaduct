package viaduct.engine.runtime

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.dataloader.DispatchingContext
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.invocationContextFor
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.mocks.ContextMocks

class DataLoaderExtensionsTest {
    @Test
    fun `combined batches retain the first input's instrumentation context`() {
        val firstInstrumentation = ResolverInstrumentationContext(mockk(), mockk())
        val secondInstrumentation = ResolverInstrumentationContext(mockk(), mockk())
        val first = ResolverInvocationContext(context(), firstInstrumentation)
        val second = ResolverInvocationContext(context(), secondInstrumentation)

        val batch = loader.invocationContextForBatchLoadFromKeys(
            setOf("a", "b"),
            environment(mapOf("a" to first, "b" to second)),
        )

        assertSame(firstInstrumentation, batch.instrumentationContext)
        assertSame(first.executionContext, batch.executionContext.invocationContextFor("a"))
        assertSame(second.executionContext, batch.executionContext.invocationContextFor("b"))
    }

    private val loader = mockk<DataLoader<String, String, Any>>()

    private fun context(): EngineExecutionContext = ContextMocks().engineExecutionContext

    private fun environment(keyContexts: Map<String, Any?>) =
        BatchLoaderEnvironment(
            keyContexts = keyContexts.mapValues { (_, context) ->
                if (context is EngineExecutionContext) ResolverInvocationContext(context, null) else context
            },
            totalKeyCount = keyContexts.size,
            dispatchingContext = object : DispatchingContext {},
        )

    @Test
    fun `withInvocationContexts exposes the context captured for each selector`() {
        val first = context()
        val second = context()

        val batch = context().withInvocationContexts(mapOf("a" to first, "b" to second))

        assertSame(first, batch.invocationContextFor("a"))
        assertSame(second, batch.invocationContextFor("b"))
    }

    @Test
    fun `invocationContextFor fails when the selector was never captured`() {
        val batch = context().withInvocationContexts(mapOf("a" to context()))

        val error = assertThrows<IllegalStateException> { batch.invocationContextFor("missing") }

        assertTrue(error.message!!.contains("No invocation context was captured for selector missing"))
    }

    @Test
    fun `invocationContextForBatchLoadFromKeys returns the sole context for a single key`() {
        val only = context()

        val result = loader.invocationContextForBatchLoadFromKeys(setOf("a"), environment(mapOf("a" to only)))

        assertSame(only, result.executionContext)
    }

    @Test
    fun `invocationContextForBatchLoadFromKeys fails when there are no keys`() {
        val error = assertThrows<IllegalStateException> {
            loader.invocationContextForBatchLoadFromKeys(emptySet(), environment(emptyMap()))
        }

        assertTrue(error.message!!.contains("No resolver invocation context provided to internalLoad"))
    }

    @Test
    fun `invocationContextForBatchLoadFromKeys fails when the first key carries a foreign context`() {
        val error = assertThrows<IllegalStateException> {
            loader.invocationContextForBatchLoadFromKeys(setOf("a"), environment(mapOf("a" to "not a context")))
        }

        assertTrue(error.message!!.contains("No resolver invocation context provided to internalLoad"))
    }

    @Test
    fun `invocationContextForBatchLoadFromKeys fails when a batched key has no context`() {
        val error = assertThrows<IllegalStateException> {
            loader.invocationContextForBatchLoadFromKeys(
                setOf("a", "b"),
                environment(mapOf("a" to context())),
            )
        }

        assertTrue(error.message!!.contains("No resolver invocation context provided for selector b"))
    }

    @Test
    fun `invocationContextForBatchLoadFromKeys clears field scope and keeps a context per key`() {
        val scoped = context().copy(
            fieldScopeSupplier = {
                EngineExecutionContextImpl.FieldExecutionScopeImpl(
                    fragments = mapOf("TestFragment" to mockk(relaxed = true)),
                    variables = mapOf("testVar" to "testValue"),
                )
            },
            currentResolver = Caller(tenantName = "tenant", typeName = "Query", fieldName = "field"),
        )
        val other = context()

        val result = loader.invocationContextForBatchLoadFromKeys(
            setOf("a", "b"),
            environment(mapOf("a" to scoped, "b" to other)),
        )

        val batch = result.executionContext
        assertTrue(batch.fieldScope.fragments.isEmpty())
        assertTrue(batch.fieldScope.variables.isEmpty())
        assertNull((batch as InternalEngineExecutionContext).impl.currentResolver)
        assertSame(scoped, batch.invocationContextFor("a"))
        assertSame(other, batch.invocationContextFor("b"))
    }
}
