package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.GraphQLObject
import viaduct.service.api.spi.GlobalIDCodec

class SimpleFieldExecutionContextTest {
    @Test
    fun `getRequestContext returns provided value`() {
        val requestContext = mapOf("key" to "value")
        val context = SimpleFieldExecutionContext(
            requestContext = requestContext
        )

        assertEquals(requestContext, context.getRequestContext())
    }

    @Test
    fun `getRequestContext returns null when not provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertNull(context.getRequestContext())
    }

    @Test
    fun `getObjectValue throws FrameworkException when no object value provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        val ex = assertThrows<FrameworkException> { context.getObjectValue() }
        assertTrue(ex.message!!.contains("Object value not available"))
    }

    @Test
    fun `getObjectValue returns provided object value`() {
        val testObject = object : GraphQLObject {}
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            objectValue = testObject
        )

        assertSame(testObject, context.getObjectValue())
    }

    @Test
    fun `getQueryValue throws FrameworkException when no query value provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        val ex = assertThrows<FrameworkException> { context.getQueryValue() }
        assertTrue(ex.message!!.contains("Query value not available"))
    }

    @Test
    fun `getArguments returns NoArguments when no arguments provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertSame(viaduct.java.api.types.Arguments.NoArguments, context.getArguments())
    }

    @Test
    fun `getArguments returns provided arguments`() {
        val args = viaduct.java.api.types.Arguments.NoArguments
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            arguments = args
        )

        assertSame(args, context.getArguments())
    }

    @Test
    fun `getSelections throws FrameworkException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        val ex = assertThrows<FrameworkException> { context.getSelections() }
        assertTrue(ex.message!!.contains("Selections access not yet implemented"))
    }

    // ── InternalContext tests ──

    @Test
    fun `getSchema returns schema from engineExecutionContext`() {
        val schema = mockk<ViaductSchema>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { fullSchema } returns schema
        }
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            engineExecutionContext = engineCtx
        )

        assertSame(schema, context.getSchema())
    }

    @Test
    fun `getSchema throws when engineExecutionContext is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        val ex = assertThrows<FrameworkException> { context.getSchema() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getGlobalIDCodec returns codec from engineExecutionContext`() {
        val codec = mockk<GlobalIDCodec>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns codec
        }
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            engineExecutionContext = engineCtx
        )

        assertSame(codec, context.getGlobalIDCodec())
    }

    @Test
    fun `getGlobalIDCodec throws when engineExecutionContext is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        val ex = assertThrows<FrameworkException> { context.getGlobalIDCodec() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getClassFinder returns provided classFinder`() {
        val finder = mockk<ResolverClassFinder>()
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            classFinder = finder
        )

        assertSame(finder, context.getClassFinder())
    }

    @Test
    fun `getClassFinder throws when classFinder is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        val ex = assertThrows<FrameworkException> { context.getClassFinder() }
        assertTrue(ex.message!!.contains("classFinder"))
    }
}
