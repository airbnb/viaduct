@file:OptIn(ExperimentalApi::class)

package viaduct.api.select

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.internalType
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi

class SelectionSetTest {
    private class Foo : Object

    private class Bar : Object

    private val fooType = Type.ofClass(Foo::class)
    private val barType = Type.ofClass(Bar::class)
    private val barField = TestCompositeField("bar", fooType, barType)
    private val fooField = TestCompositeField("foo", barType, fooType)

    @Test
    fun empty() {
        val ss = SelectionSet.empty(fooType)
        assertEquals(fooType, ss.internalType())
        assertTrue(ss.selectedFieldCoordinates().isEmpty())
        assertFalse(ss.contains(barField))
        assertFalse(ss.requestsType(fooType))
    }

    @Test
    fun `empty selections retain field types through navigation`() {
        val selections = SelectionSet.empty(fooType)

        val barSelections = selections.selectionSetFor(barField)
        val nestedFooSelections = barSelections.selectionSetFor(fooField)

        assertEquals(barType, barSelections.internalType())
        assertTrue(barSelections.selectedFieldCoordinates().isEmpty())
        assertFalse(barSelections.contains(fooField))
        assertFalse(barSelections.requestsType(barType))
        assertEquals(fooType, nestedFooSelections.internalType())
        assertTrue(nestedFooSelections.selectedFieldCoordinates().isEmpty())
        assertFalse(nestedFooSelections.contains(barField))
        assertFalse(nestedFooSelections.requestsType(fooType))
    }

    @Test
    fun noSelections() {
        val ss = SelectionSet.NoSelections
        val type = ss.internalType()
        val field = TestCompositeField("foo", type, fooType)

        assertTrue(ss.selectedFieldCoordinates().isEmpty())
        assertFalse(ss.contains(field))
        assertFalse(ss.requestsType(type))
        assertTrue(type.name.startsWith("__"))
        assertEquals(CompositeOutput.NotComposite::class, type.kcls)
        assertThrows<UnsupportedOperationException> { ss.selectionSetFor(field) }
    }

    @Test
    fun `custom selections cannot impersonate NoSelections through equality`() {
        val selections = object : SelectionSet<Foo> by SelectionSet.empty(fooType) {
            override fun equals(other: Any?): Boolean = this === other || other === SelectionSet.NoSelections

            override fun hashCode(): Int = SelectionSet.NoSelections.hashCode()
        }

        val failure = assertThrows<IllegalArgumentException> { selections.internalType() }

        assertEquals(
            "Custom SelectionSet implementations used by the framework must implement InternalSelectionSet",
            failure.message,
        )
    }

    private data class TestCompositeField<T : CompositeOutput, R : CompositeOutput>(
        override val name: String,
        override val containingType: Type<T>,
        override val type: Type<R>,
    ) : CompositeField<T, R>
}
