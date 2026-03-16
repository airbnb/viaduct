package viaduct.java.runtime.bridge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import viaduct.java.api.types.GraphQLObject

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
    fun `getObjectValue throws UnsupportedOperationException when no object value provided`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getObjectValue() }
            .isInstanceOf(UnsupportedOperationException::class.java)
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
    fun `getQueryValue throws UnsupportedOperationException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getQueryValue() }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("Query value access not yet implemented")
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
    fun `getSelections throws UnsupportedOperationException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getSelections() }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("Selections access not yet implemented")
    }
}
