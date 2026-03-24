package viaduct.api.select

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import viaduct.api.types.Object

class SelectionSetTest {
    @Test
    fun empty() {
        open class Foo : Object
        val fooType = Type.ofClass(Foo::class)

        class Bar : Foo()
        val barType = Type.ofClass(Bar::class)

        val ss = SelectionSet.empty(fooType)
        assertEquals(fooType, ss.type)
        assertTrue(ss.isEmpty())
        assertFalse(ss.requestsType(fooType))
        assertTrue(ss.selectionSetFor(fooType).isEmpty())
        assertTrue(ss.selectionSetFor(barType).isEmpty())
    }

    @Test
    fun noSelections() {
        val ss = SelectionSet.NoSelections
        assertTrue(ss.isEmpty())
        assertFalse(ss.requestsType(ss.type))
        assertTrue(ss.type.name.startsWith("__"))
    }
}
