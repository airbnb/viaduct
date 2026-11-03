package viaduct.engine.api

import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParentSuppliedValueTest {
    private val type = GraphQLObjectType.newObject().name("Listing").build()

    @Test
    fun `stores the value and the supplied-fields check`() {
        val inner = object {}
        val wrapper = ParentSuppliedValue(inner) { objectType, fieldName -> objectType.name == "Listing" && fieldName == "title" }

        assertSame(inner, wrapper.value)
        assertTrue(wrapper.isSupplied(type, "title"))
        assertFalse(wrapper.isSupplied(type, "status"))
    }

    @Test
    fun `stores null value`() {
        assertNull(ParentSuppliedValue(null) { _, _ -> false }.value)
    }

    @Test
    fun `throws when wrapping another ParentSuppliedValue`() {
        assertThrows<IllegalArgumentException> {
            ParentSuppliedValue(ParentSuppliedValue("x") { _, _ -> false }) { _, _ -> false }
        }
    }

    @Test
    fun `throws when wrapping a ParentManagedValue`() {
        assertThrows<IllegalArgumentException> {
            ParentSuppliedValue(ParentManagedValue("x")) { _, _ -> false }
        }
    }

    @Test
    fun `throws when wrapping a StandardResolutionValue`() {
        assertThrows<IllegalArgumentException> {
            ParentSuppliedValue(StandardResolutionValue("x")) { _, _ -> false }
        }
    }
}
