package viaduct.java.runtime.bridge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

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
    fun `getObjectValue throws UnsupportedOperationException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getObjectValue() }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("Object value access not yet implemented")
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
    fun `getArguments throws UnsupportedOperationException`() {
        val context = SimpleFieldExecutionContext(
            requestContext = null
        )

        assertThatThrownBy { context.getArguments() }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("Arguments access not yet implemented")
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
