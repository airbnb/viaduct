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
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.mocks.ContextMocks

class DataLoaderExtensionsTest {
    private val loader = mockk<DataLoader<String, String, Any>>()

    private fun context(): EngineExecutionContext = ContextMocks().engineExecutionContext

    private fun environment(keyContexts: Map<String, Any?>) =
        BatchLoaderEnvironment(
            keyContexts = keyContexts,
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
    fun `invocationContextFor returns the receiver when it carries no invocation contexts`() {
        val plain = context()

        assertSame(plain, plain.invocationContextFor("anything"))
    }

    @Test
    fun `executionContextForBatchLoadFromKeys returns the sole context for a single key`() {
        val only = context()

        val result = loader.executionContextForBatchLoadFromKeys(setOf("a"), environment(mapOf("a" to only)))

        assertSame(only, result)
    }

    @Test
    fun `executionContextForBatchLoadFromKeys fails when there are no keys`() {
        val error = assertThrows<IllegalStateException> {
            loader.executionContextForBatchLoadFromKeys(emptySet(), environment(emptyMap()))
        }

        assertTrue(error.message!!.contains("No EngineExecutionContext provided to internalLoad"))
    }

    @Test
    fun `executionContextForBatchLoadFromKeys fails when the first key carries a foreign context`() {
        val error = assertThrows<IllegalStateException> {
            loader.executionContextForBatchLoadFromKeys(setOf("a"), environment(mapOf("a" to "not a context")))
        }

        assertTrue(error.message!!.contains("No EngineExecutionContext provided to internalLoad"))
    }

    @Test
    fun `executionContextForBatchLoadFromKeys fails when a batched key has no context`() {
        val error = assertThrows<IllegalStateException> {
            loader.executionContextForBatchLoadFromKeys(
                setOf("a", "b"),
                environment(mapOf("a" to context())),
            )
        }

        assertTrue(error.message!!.contains("No EngineExecutionContext provided for selector b"))
    }

    @Test
    fun `executionContextForBatchLoadFromKeys clears field scope and keeps a context per key`() {
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

        val result = loader.executionContextForBatchLoadFromKeys(
            setOf("a", "b"),
            environment(mapOf("a" to scoped, "b" to other)),
        )

        assertTrue(result.fieldScope.fragments.isEmpty())
        assertTrue(result.fieldScope.variables.isEmpty())
        assertNull((result as InternalEngineExecutionContext).impl.currentResolver)
        assertSame(scoped, result.invocationContextFor("a"))
        assertSame(other, result.invocationContextFor("b"))
    }
}
