@file:OptIn(ExperimentalApi::class)

package viaduct.api.reflect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.RootObjectFieldImpl
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi

class RootObjectFieldImplTest {
    private class Foo : GRT

    private val foo = Type.ofClass(Foo::class)

    private class Bar : Object

    private val bar = Type.ofClass(Bar::class)

    @Test
    fun `properties are set correctly`() {
        val f = RootObjectFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar, listOf("field"))
        assertEquals("field", f.name)
        assertEquals(foo, f.containingType)
        assertEquals(bar, f.type)
        assertEquals(listOf("field"), f.pathFromQueryRoot)
    }

    @Test
    fun `is a subtype of CompositeField`() {
        val f: CompositeField<Foo, Bar> = RootObjectFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar, listOf("field"))
        assertEquals("field", f.name)
    }

    @Test
    fun `is a subtype of RootObjectField`() {
        val f: RootObjectField<Foo, Bar, Arguments.NoArguments> = RootObjectFieldImpl("field", foo, bar, listOf("field"))
        assertEquals("field", f.name)
    }

    @Test
    fun `rootFieldPath with nested namespace path`() {
        val f = RootObjectFieldImpl<Foo, Bar, Arguments.NoArguments>("create", foo, bar, listOf("_factories", "products", "create"))
        assertEquals(listOf("_factories", "products", "create"), f.pathFromQueryRoot)
    }

    @Test
    fun `rejects empty rootFieldPath`() {
        assertThrows<IllegalArgumentException> {
            RootObjectFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar, emptyList())
        }
    }

    @Test
    fun `rejects rootFieldPath whose last element does not match name`() {
        assertThrows<IllegalArgumentException> {
            RootObjectFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar, listOf("other"))
        }
    }
}
