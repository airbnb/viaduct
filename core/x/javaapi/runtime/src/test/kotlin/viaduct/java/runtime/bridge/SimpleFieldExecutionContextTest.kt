package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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

        assertThat(context.getRequestContext()).isEqualTo(requestContext)
    }

    @Test
    fun `getRequestContext returns null when not provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThat(context.getRequestContext()).isNull()
    }

    @Test
    fun `getObjectValue throws FrameworkException when no object value provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getObjectValue() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("Object value not available")
    }

    @Test
    fun `getObjectValue returns provided object value`() {
        val testObject = object : GraphQLObject {}
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            objectValue = testObject
        )

        assertThat(context.getObjectValue()).isSameAs(testObject)
    }

    @Test
    fun `getQueryValue throws FrameworkException when no query value provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getQueryValue() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("Query value not available")
    }

    @Test
    fun `getArguments returns NoArguments when no arguments provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThat(context.getArguments()).isSameAs(viaduct.java.api.types.Arguments.NoArguments)
    }

    @Test
    fun `getArguments returns provided arguments`() {
        val args = viaduct.java.api.types.Arguments.NoArguments
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            arguments = args
        )

        assertThat(context.getArguments()).isSameAs(args)
    }

    @Test
    fun `getSelections throws FrameworkException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getSelections() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("Selections access not yet implemented")
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

        assertThat(context.getSchema()).isSameAs(schema)
    }

    @Test
    fun `getSchema throws when engineExecutionContext is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        assertThatThrownBy { context.getSchema() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("engineExecutionContext")
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

        assertThat(context.getGlobalIDCodec()).isSameAs(codec)
    }

    @Test
    fun `getGlobalIDCodec throws when engineExecutionContext is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        assertThatThrownBy { context.getGlobalIDCodec() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("engineExecutionContext")
    }

    @Test
    fun `getClassFinder returns provided classFinder`() {
        val finder = mockk<ResolverClassFinder>()
        val context = SimpleFieldExecutionContext(
            requestContext = null,
            classFinder = finder
        )

        assertThat(context.getClassFinder()).isSameAs(finder)
    }

    @Test
    fun `getClassFinder throws when classFinder is null`() {
        val context = SimpleFieldExecutionContext(requestContext = null)

        assertThatThrownBy { context.getClassFinder() }
            .isInstanceOf(FrameworkException::class.java)
            .hasMessageContaining("classFinder")
    }
}
