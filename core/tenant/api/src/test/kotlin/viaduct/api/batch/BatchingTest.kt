@file:OptIn(ExperimentalApi::class)

package viaduct.api.batch

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi

class BatchingTest {
    private val nodeType = Type.ofClass(TestNode::class)
    private val specializedNodeType = Type.ofClass(SpecializedNode::class)
    private val detailsType = Type.ofClass(Details::class)
    private val nameField = TestField("name", nodeType)
    private val priceField = TestField("price", nodeType)
    private val detailsField = TestCompositeField("details", nodeType, detailsType)
    private val summaryField = TestField("summary", detailsType)
    private val ratingField = TestField("rating", detailsType)
    private val specializedField = TestField("specialized", specializedNodeType)

    @Test
    fun `same-selection batching preserves group order, context keys, and omissions`() =
        runTest {
            val firstSelection = TestSelectionSet(nodeType, setOf(nameField))
            val equalSelection = TestSelectionSet(nodeType, setOf(nameField))
            val otherSelection = TestSelectionSet(nodeType, setOf(priceField))
            val first = TestContext("first", firstSelection)
            val second = TestContext("second", otherSelection)
            val third = TestContext("third", equalSelection)
            val firstValue = value("first")
            val secondValue = value("second")
            val groups = mutableListOf<List<TestContext>>()

            val result = batchBySameSelection(listOf(first, second, third)) { group ->
                groups += group.contexts
                if (group.contexts.first() === first) {
                    mapOf(first to firstValue)
                } else {
                    mapOf(second to secondValue)
                }
            }

            assertEquals(2, groups.size)
            assertSame(first, groups[0][0])
            assertSame(third, groups[0][1])
            assertSame(second, groups[1][0])
            assertEquals(2, result.size)
            assertSame(firstValue, result[first])
            assertSame(secondValue, result[second])
            assertFalse(result.containsKey(third))
            assertTrue(result.containsKey(TestContext("first", firstSelection)))
        }

    @Test
    fun `result keys use normal map equality`() =
        runTest {
            val selection = TestSelectionSet(nodeType, setOf(nameField))
            val original = TestContext("same-value", selection)
            val recreated = TestContext("same-value", selection)
            val recreatedValue = value("recreated")

            val result = batchBySameSelection(listOf(original)) {
                mapOf(recreated to recreatedValue)
            }

            assertSame(recreatedValue, result[original])
        }

    @Test
    fun `result keys from another group are rejected`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(priceField)))

            assertThrows<IllegalArgumentException> {
                batchBySameSelection(listOf(first, second)) {
                    mapOf(second to value("second"))
                }
            }
        }

    @Test
    fun `own-field batching groups different nested selections and exposes their union`() =
        runTest {
            val summarySelection = TestSelectionSet(detailsType, setOf(summaryField))
            val ratingSelection = TestSelectionSet(detailsType, setOf(ratingField))
            val first = TestContext(
                "first",
                TestSelectionSet(
                    type = nodeType,
                    fields = setOf(detailsField),
                    fieldSelections = mapOf(detailsField to summarySelection),
                ),
            )
            val second = TestContext(
                "second",
                TestSelectionSet(
                    type = nodeType,
                    fields = setOf(detailsField),
                    fieldSelections = mapOf(detailsField to ratingSelection),
                ),
            )
            var calls = 0

            val result = batchByOwnFields(listOf(first, second)) { group ->
                calls++
                assertEquals(1, group.key.size)
                assertTrue(group.key.contains(detailsField.coordinate()))
                assertTrue(group.selections.selectionSetFor(detailsField).contains(summaryField))
                assertTrue(group.selections.selectionSetFor(detailsField).contains(ratingField))
                mapOf(
                    first to value("first"),
                    second to value("second"),
                )
            }

            assertEquals(1, calls)
            assertEquals(2, result.size)
        }

    @Test
    fun `custom batching groups by tenant key in first-key order`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(detailsField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(nameField)))
            val third = TestContext("third", TestSelectionSet(nodeType, setOf(detailsField, priceField)))
            val groups = mutableListOf<Pair<Boolean, List<TestContext>>>()

            val result = batchByCustomGrouping(
                contexts = listOf(first, second, third),
                groupBy = { it.contains(detailsField) },
            ) { group ->
                groups += group.key to group.contexts
                emptyMap()
            }

            assertTrue(result.isEmpty())
            assertEquals(2, groups.size)
            assertTrue(groups[0].first)
            assertSame(first, groups[0].second[0])
            assertSame(third, groups[0].second[1])
            assertFalse(groups[1].first)
            assertSame(second, groups[1].second[0])
        }

    @Test
    fun `batch selection view uses any-member semantics through field and type navigation`() {
        val detailsSelection = TestSelectionSet(detailsType, setOf(summaryField))
        val specializedSelection = TestSelectionSet(specializedNodeType, setOf(specializedField))
        val first = TestContext(
            "first",
            TestSelectionSet(
                type = nodeType,
                fields = setOf(nameField, detailsField),
                requestedTypes = setOf(nodeType.name, specializedNodeType.name),
                fieldSelections = mapOf(detailsField to detailsSelection),
                typeSelections = mapOf(specializedNodeType.name to specializedSelection),
            ),
        )
        val second = TestContext(
            "second",
            TestSelectionSet(nodeType, setOf(priceField)),
        )

        val selections = listOf(first, second).selections()

        assertSame(nodeType, selections.type)
        assertFalse(selections.isEmpty())
        assertTrue(selections.contains(nameField))
        assertTrue(selections.contains(priceField))
        assertTrue(selections.selectedFieldCoordinates().contains(detailsField.coordinate()))
        assertTrue(selections.requestsType(specializedNodeType))
        assertTrue(selections.selectionSetFor(detailsField).contains(summaryField))
        assertTrue(
            selections
                .selectionSetFor(specializedNodeType)
                .contains(specializedField)
        )
    }

    @Test
    fun `empty batches return an empty map without invoking the callback`() =
        runTest {
            var called = false

            val result = batchByCustomGrouping<TestNode, TestContext, Unit>(
                contexts = emptyList(),
                groupBy = { },
            ) {
                called = true
                emptyMap()
            }

            assertTrue(result.isEmpty())
            assertFalse(called)
            assertThrows<IllegalArgumentException> {
                emptyList<TestContext>().selections()
            }
        }

    private fun value(value: String): FieldValue<TestNode> = FieldValue.ofValue(TestNode(value))

    private open class TestNode(val value: String = "") : NodeObject

    private class SpecializedNode : TestNode()

    private class Details : CompositeOutput

    private data class TestField<T : GRT>(
        override val name: String,
        override val containingType: Type<T>,
    ) : Field<T>

    private data class TestCompositeField<T : GRT, R : GRT>(
        override val name: String,
        override val containingType: Type<T>,
        override val type: Type<R>,
    ) : CompositeField<T, R>

    private data class TestSelectionSet<T : CompositeOutput>(
        override val type: Type<T>,
        private val fields: Set<Field<out T>> = emptySet(),
        private val requestedTypes: Set<String> = setOf(type.name),
        private val fieldSelections: Map<Field<*>, SelectionSet<*>> = emptyMap(),
        private val typeSelections: Map<String, SelectionSet<*>> = emptyMap(),
    ) : SelectionSet<T> {
        override fun selectedFieldCoordinates(): Set<FieldCoordinate> = fields.mapTo(linkedSetOf()) { FieldCoordinate(it.containingType.name, it.name) }

        override fun <U : T> contains(field: Field<U>): Boolean = fields.contains(field)

        override fun <U : T> requestsType(type: Type<U>): Boolean = type.name in requestedTypes

        @Suppress("UNCHECKED_CAST")
        override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> = fieldSelections[field] as? SelectionSet<R> ?: SelectionSet.empty(field.type)

        @Suppress("UNCHECKED_CAST")
        override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> =
            when {
                type == this.type -> this as SelectionSet<U>
                else -> typeSelections[type.name] as? SelectionSet<U> ?: SelectionSet.empty(type)
            }

        override fun isEmpty(): Boolean = fields.isEmpty()
    }

    private class TestContext(
        private val value: String,
        private val selectionSet: SelectionSet<TestNode>,
        delegate: SelectiveNodeExecutionContext<TestNode> = mockk(relaxed = true),
    ) : SelectiveNodeExecutionContext<TestNode> by delegate {
        override fun selections(): SelectionSet<TestNode> = selectionSet

        override fun equals(other: Any?): Boolean = other is TestContext && value == other.value

        override fun hashCode(): Int = value.hashCode()
    }

    private fun Field<*>.coordinate(): FieldCoordinate = FieldCoordinate(containingType.name, name)
}
