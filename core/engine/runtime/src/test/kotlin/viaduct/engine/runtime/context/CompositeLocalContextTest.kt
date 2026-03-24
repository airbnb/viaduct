package viaduct.engine.runtime.context

import graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeLocalContextTest {
    private data class TestContext(val data: Map<String, String> = mapOf())

    private data class OtherTestContext(val data: Map<String, String> = mapOf())

    @Test
    fun `can get context object from CompositeLocalContext`() {
        val ctx = CompositeLocalContext.withContexts(TestContext(mapOf("a" to "b")))
        val testCtx = ctx.get<TestContext>()
        assertNotNull(testCtx)
    }

    @Test
    fun `adds new context in CompositeLocalContext`() {
        val ctx = CompositeLocalContext.empty
        val updatedCtx = ctx.addOrUpdate(TestContext(mapOf("a" to "b")))
        assertEquals(mapOf("a" to "b"), updatedCtx.get<TestContext>()?.data)
    }

    @Test
    fun `can update existing context in CompositeLocalContext`() {
        val ctx = CompositeLocalContext.withContexts(TestContext(mapOf("a" to "b")))
        val updatedCtx = ctx.addOrUpdate(TestContext(mapOf("c" to "d")))
        assertEquals(mapOf("c" to "d"), updatedCtx.get<TestContext>()?.data)
    }

    @Test
    fun `can update existing context in CompositeLocalContext with multiple contexts`() {
        val ctx =
            CompositeLocalContext.withContexts(
                TestContext(mapOf("a" to "b")),
                OtherTestContext(mapOf("c" to "d"))
            )
        val updatedCtx = ctx.addOrUpdate(TestContext(mapOf("e" to "f")))
        assertEquals(mapOf("e" to "f"), updatedCtx.get<TestContext>()?.data)
    }

    @Test
    fun `CompositeLocalContext is immutable`() {
        val base = CompositeLocalContext.empty
        val next = base.addOrUpdate(TestContext())
        assertNotEquals(base, next)
        assertNotSame(base, next)
    }

    @Test
    fun `find will throw if there is no context`() {
        val subject = newDataFetchingEnvironment().build()
        assertThrows<Exception> {
            subject.findLocalContextForType<TestContext>()
        }
    }

    @Test
    fun `find will throw if the context is not composite`() {
        val subject = newDataFetchingEnvironment().localContext("Hi").build()
        assertThrows<Exception> {
            subject.findLocalContextForType<TestContext>()
        }
    }

    @Test
    fun `find will throw if the context does not contain type requested`() {
        val subject = newDataFetchingEnvironment().localContext(CompositeLocalContext.empty).build()
        assertThrows<Exception> {
            subject.findLocalContextForType<TestContext>()
        }
    }

    @Test
    fun `find works in expected case`() {
        val ctx = TestContext()
        val compositeCtx = CompositeLocalContext.withContexts(ctx)
        val subject = newDataFetchingEnvironment().localContext(compositeCtx).build()
        assertSame(ctx, subject.findLocalContextForType<TestContext>())
    }
}
