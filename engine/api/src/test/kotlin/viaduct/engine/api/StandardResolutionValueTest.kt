package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StandardResolutionValueTest {
    @Test
    fun `stores non-null value`() {
        val inner = object {}
        val wrapper = StandardResolutionValue(inner)
        assertSame(inner, wrapper.value)
    }

    @Test
    fun `stores null value`() {
        val wrapper = StandardResolutionValue(null)
        assertNull(wrapper.value)
    }

    @Test
    fun `throws when wrapping another StandardResolutionValue`() {
        val inner = StandardResolutionValue("x")
        assertThrows<IllegalArgumentException> {
            StandardResolutionValue(inner)
        }
    }

    @Test
    fun `throws when wrapping a ParentManagedValue`() {
        val inner = ParentManagedValue("x")
        assertThrows<IllegalArgumentException> {
            StandardResolutionValue(inner)
        }
    }

    @Test
    fun `is accessible when boxed as Any`() {
        // Assigning to Any forces the box-impl path of the @JvmInline value class,
        // covering the private boxed constructor that is otherwise unreachable from
        // tests that only use the value in a statically-typed context.
        val boxed: Any = StandardResolutionValue("test")
        assertTrue(boxed is StandardResolutionValue)
        assertSame("test", (boxed as StandardResolutionValue).value)
    }
}
