@file:OptIn(ExperimentalApi::class)

package viaduct.api.select

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi

class SelectionSetTest {
    @Test
    fun empty() {
        open class Foo : Object
        val fooType = Type.ofClass(Foo::class)

        val ss = SelectionSet.empty(fooType)
        assertEquals(fooType, ss.type)
        assertTrue(ss.selectedFieldCoordinates().isEmpty())
        assertFalse(ss.requestsType(fooType))
    }

    @Test
    fun noSelections() {
        val ss = SelectionSet.NoSelections
        assertTrue(ss.selectedFieldCoordinates().isEmpty())
        assertFalse(ss.requestsType(ss.type))
        assertTrue(ss.type.name.startsWith("__"))
    }
}
