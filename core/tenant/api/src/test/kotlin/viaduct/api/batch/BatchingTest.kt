@file:OptIn(ExperimentalApi::class, InternalApi::class)

package viaduct.api.batch

import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.internal.internalType
import viaduct.api.mocks.MockSelectionSetFactory
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

class BatchingTest {
    private val nodeType = Type.ofClass(TestNode::class)
    private val specializedNodeType = Type.ofClass(SpecializedNode::class)
    private val detailsType = Type.ofClass(Details::class)
    private val nameField = TestField("name", nodeType)
    private val priceField = TestField("price", nodeType)
    private val detailsField = TestCompositeField("details", nodeType, detailsType)
    private val summaryField = TestField("summary", detailsType)
    private val ratingField = TestField("rating", detailsType)

    @Test
    fun `same-selection batching groups equal selections and preserves context order, keys, and omissions`() =
        runTest {
            val firstSelection = TestSelectionSet(nodeType, setOf(nameField))
            val equalSelection = TestSelectionSet(nodeType, setOf(nameField))
            val otherSelection = TestSelectionSet(nodeType, setOf(priceField))
            val first = TestContext("first", firstSelection)
            val second = TestContext("second", otherSelection)
            val third = TestContext("third", equalSelection)
            val firstValue = value("first")
            val secondValue = value("second")
            val groups = ConcurrentLinkedQueue<List<TestContext>>()

            val result = batchBySameSelection(listOf(first, second, third)) { group ->
                groups += group.contexts
                if (group.contexts.first() === first) {
                    mapOf(first to firstValue)
                } else {
                    mapOf(second to secondValue)
                }
            }

            assertEquals(2, groups.size)
            assertEquals(setOf(listOf(first, third), listOf(second)), groups.toSet())
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
    fun `result keys from another group fail every context of the returning group only`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val firstSibling = TestContext("first-sibling", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(priceField)))
            val secondValue = value("second")

            val result = batchBySameSelection(listOf(first, second, firstSibling)) {
                mapOf(second to secondValue)
            }

            listOf(first, firstSibling).forEach { context ->
                val failure = assertThrows<IllegalArgumentException> { result.getValue(context).get() }
                assertEquals("Batch group returned a context that was not a member of that group", failure.message)
            }
            assertSame(secondValue, result[second])
        }

    @Test
    fun `a failing group resolves its contexts to the failure without affecting other groups`() =
        runTest {
            val failing = TestContext("failing", TestSelectionSet(nodeType, setOf(nameField)))
            val failingSibling = TestContext("failing-sibling", TestSelectionSet(nodeType, setOf(nameField)))
            val succeeding = TestContext("succeeding", TestSelectionSet(nodeType, setOf(priceField)))
            val failure = IllegalStateException("backend unavailable")
            val succeedingValue = value("succeeding")

            val result = batchBySameSelection(listOf(failing, succeeding, failingSibling)) { group ->
                if (group.contexts.first() === failing) throw failure
                yield()
                mapOf(succeeding to succeedingValue)
            }

            assertSame(failure, assertThrows<IllegalStateException> { result.getValue(failing).get() })
            assertSame(failure, assertThrows<IllegalStateException> { result.getValue(failingSibling).get() })
            assertSame(succeedingValue, result[succeeding])
        }

    @Test
    fun `a timeout in one group resolves only that group to the timeout`() =
        runTest {
            val timingOut = TestContext("timing-out", TestSelectionSet(nodeType, setOf(nameField)))
            val succeeding = TestContext("succeeding", TestSelectionSet(nodeType, setOf(priceField)))
            val succeedingValue = value("succeeding")
            val timeout = CompletableDeferred<TimeoutCancellationException>()

            val result = batchBySameSelection(listOf(timingOut, succeeding)) { group ->
                if (group.contexts.single() === timingOut) {
                    timeOutRecording(timeout)
                }
                mapOf(succeeding to succeedingValue)
            }

            assertSame(timeout.await(), assertThrows<TimeoutCancellationException> { result.getValue(timingOut).get() })
            assertSame(succeedingValue, result[succeeding])
        }

    @Test
    fun `a timeout in a single group resolves its contexts to the timeout`() =
        runTest {
            val context = TestContext("only", TestSelectionSet(nodeType, setOf(nameField)))
            val timeout = CompletableDeferred<TimeoutCancellationException>()

            val result = batchBySameSelection(listOf(context)) {
                timeOutRecording(timeout)
                emptyMap()
            }

            assertSame(timeout.await(), assertThrows<TimeoutCancellationException> { result.getValue(context).get() })
        }

    @Test
    fun `an exception from groupBy fails the whole call`() =
        runTest {
            val failure = IllegalStateException("bad grouping key")
            var called = false

            val thrown = assertThrows<IllegalStateException> {
                batchByCustomGrouping<TestNode, TestContext, Unit>(
                    contexts = listOf(TestContext("only", TestSelectionSet(nodeType, setOf(nameField)))),
                    groupBy = { throw failure },
                ) {
                    called = true
                    emptyMap()
                }
            }

            assertSame(failure, thrown)
            assertFalse(called)
        }

    @Test
    fun `an error from a group's block fails the whole call`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(priceField)))

            val thrown = assertThrows<NotImplementedError> {
                batchBySameSelection(listOf(first, second)) { group ->
                    if (group.contexts.single() === first) TODO("unimplemented group")
                    emptyMap()
                }
            }

            assertEquals("An operation is not implemented: unimplemented group", thrown.message)
        }

    @Test
    fun `cancelling the caller cancels every group`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(priceField)))
            val started = mapOf(first to CompletableDeferred<Unit>(), second to CompletableDeferred())
            val cancelled = mapOf(first to CompletableDeferred<Unit>(), second to CompletableDeferred())
            var completed = false

            val job = launch {
                batchBySameSelection(listOf(first, second)) { group ->
                    val context = group.contexts.single()
                    started.getValue(context).complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.getValue(context).complete(Unit)
                    }
                }
                completed = true
            }
            started.values.awaitAll()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(completed)
            assertTrue(cancelled.values.all { it.isCompleted })
        }

    @Test
    fun `cancelling the caller during a single group's block stops the batch call`() =
        runTest {
            val context = TestContext("only", TestSelectionSet(nodeType, setOf(nameField)))
            val started = CompletableDeferred<Unit>()
            var completed = false

            val job = launch {
                batchBySameSelection(listOf(context)) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                completed = true
            }
            started.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(completed)
        }

    @Test
    fun `a failing single group resolves its contexts to the failure`() =
        runTest {
            val context = TestContext("only", TestSelectionSet(nodeType, setOf(nameField)))
            val failure = IllegalStateException("backend unavailable")

            val result = batchBySameSelection(listOf(context)) { throw failure }

            assertSame(failure, assertThrows<IllegalStateException> { result.getValue(context).get() })
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
    fun `custom batching groups by tenant key and preserves context order`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(detailsField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(nameField)))
            val third = TestContext("third", TestSelectionSet(nodeType, setOf(detailsField, priceField)))
            val groups = ConcurrentLinkedQueue<Pair<Boolean, List<TestContext>>>()

            val result = batchByCustomGrouping(
                contexts = listOf(first, second, third),
                groupBy = { it.contains(detailsField) },
            ) { group ->
                groups += group.key to group.contexts
                emptyMap()
            }

            assertTrue(result.isEmpty())
            assertEquals(2, groups.size)
            assertEquals(setOf(true to listOf(first, third), false to listOf(second)), groups.toSet())
        }

    @Test
    fun `groups resolve concurrently`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(priceField)))

            var inFlight = 0
            var maxInFlight = 0

            val result = batchBySameSelection(listOf(first, second)) { group ->
                maxInFlight = maxOf(maxInFlight, ++inFlight)
                yield()
                inFlight--
                group.contexts.associateWith { value("resolved") }
            }

            assertEquals(2, maxInFlight)
            assertEquals(setOf(first, second), result.keys)
        }

    @Test
    fun `a single group resolves in the caller's coroutine`() =
        runTest {
            val first = TestContext("first", TestSelectionSet(nodeType, setOf(nameField)))
            val second = TestContext("second", TestSelectionSet(nodeType, setOf(nameField)))
            val callerJob = currentCoroutineContext()[Job]
            var resolveJob: Job? = null

            batchBySameSelection(listOf(first, second)) {
                resolveJob = currentCoroutineContext()[Job]
                emptyMap()
            }

            assertSame(callerJob, resolveJob)
        }

    @Test
    fun `batch selection view uses any-member semantics through field navigation`() {
        val detailsSelection = TestSelectionSet(detailsType, setOf(summaryField))
        val first = TestContext(
            "first",
            TestSelectionSet(
                type = nodeType,
                fields = setOf(nameField, detailsField),
                requestedTypes = setOf(nodeType.name, specializedNodeType.name),
                fieldSelections = mapOf(detailsField to detailsSelection),
            ),
        )
        val second = TestContext(
            "second",
            TestSelectionSet(nodeType, setOf(priceField)),
        )

        val selections = listOf(first, second).selections()

        assertSame(nodeType, selections.internalType())
        assertTrue(selections.contains(nameField))
        assertTrue(selections.contains(priceField))
        assertTrue(selections.selectedFieldCoordinates().contains(detailsField.coordinate()))
        assertTrue(selections.requestsType(specializedNodeType))
        assertTrue(selections.selectionSetFor(detailsField).contains(summaryField))
    }

    @Test
    fun `batch selection view combines empty and non-engine selections with matching root types`() {
        val contexts = listOf(
            TestContext("empty", SelectionSet.empty(nodeType)),
            TestContext("selected", TestSelectionSet(nodeType, setOf(nameField))),
        )

        val selections = contexts.selections()

        assertSame(nodeType, selections.internalType())
        assertTrue(selections.contains(nameField))
        assertFalse(selections.contains(priceField))
    }

    @Test
    fun `batch selection view accepts selections from the mock factory`() {
        val factory = MockSelectionSetFactory(mockk())
        val contexts = listOf(
            TestContext("mock", factory.selectionsOn(nodeType, "name", emptyMap())),
            TestContext("empty", SelectionSet.empty(nodeType)),
        )

        val selections = contexts.selections()

        assertSame(nodeType, selections.internalType())
        assertTrue(selections.contains(nameField))
    }

    @Test
    fun `batch selection view rejects mismatched root types`() {
        val otherType = object : Type<TestNode> {
            override val name = "OtherNode"
            override val kcls = TestNode::class
        }
        val contexts = listOf(
            TestContext("first", TestSelectionSet(nodeType)),
            TestContext("second", TestSelectionSet(otherType)),
        )

        val failure = assertThrows<IllegalArgumentException> { contexts.selections() }

        assertEquals("All selections in a batch view must describe the same type", failure.message)
    }

    @Test
    fun `custom selections without metadata remain inspectable but cannot be batched`() {
        val selections = object : SelectionSet<TestNode> by TestSelectionSet(nodeType, setOf(nameField)) {}
        assertTrue(selections.contains(nameField))

        val failure = assertThrows<IllegalArgumentException> {
            listOf(TestContext("custom", selections)).selections()
        }

        assertEquals(
            "Custom SelectionSet implementations used by the framework must implement InternalSelectionSet",
            failure.message,
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

    private suspend fun timeOutRecording(timeout: CompletableDeferred<TimeoutCancellationException>) {
        try {
            withTimeout(1) { delay(1_000) }
        } catch (e: TimeoutCancellationException) {
            timeout.complete(e)
            throw e
        }
    }

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
    ) : SelectionSet<T>, InternalSelectionSet<T> {
        override fun selectedFieldCoordinates(): Set<FieldCoordinate> = fields.mapTo(linkedSetOf()) { FieldCoordinate(it.containingType.name, it.name) }

        override fun <U : T> contains(field: Field<U>): Boolean = fields.contains(field)

        override fun <U : T> requestsType(type: Type<U>): Boolean = type.name in requestedTypes

        @Suppress("UNCHECKED_CAST")
        override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> = fieldSelections[field] as? SelectionSet<R> ?: SelectionSet.empty(field.type)
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
