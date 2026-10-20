package viaduct.engine.runtime2.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.model.testing.TestWorld

class SelectionForestConcatenationTest {
    @Test
    fun `empty and singleton forests retain their observations through transformations`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = world.assumptions.schema.requireQueryTypeDef()
        val selection = Selection.of(ObjectEngineResult.Key.of(query.requireField("value"), emptyMap()), setOf(query), selectionForestOf())
        val empty = selectionForestOf()

        for (forest in listOf(empty, emptyList<Selection>().toSelectionForest())) {
            assertTrue(forest.isEmpty())
            assertEquals(0, forest.size)
            assertTrue(forest.all { error("Empty predicate invoked") })
            forest.forEach { error("Empty action invoked") }
            assertTrue(forest.filter { error("Empty predicate invoked") }.isEmpty())
            assertTrue(forest.flatMap { error("Empty transform invoked") }.isEmpty())
            assertTrue(forest.mapSelections { error("Empty transform invoked") }.isEmpty())
            assertThrows<NoSuchElementException> { forest.single() }
        }
        for (forest in listOf(selectionForestOf(selection), selectionForestOf(*arrayOf(selection)), listOf(selection).toSelectionForest())) {
            assertEquals(1, forest.size)
            assertSame(selection, forest.single())
            assertSame(selection, forest.filter { true }.single())
            assertTrue(forest.filter { false }.isEmpty())
            assertTrue(forest.mapNotNullSelections { null }.isEmpty())
            assertEquals(2, forest.flatMap { selectionForestOf(it, it) }.size)
            var visits = 0
            assertSame(
                selection,
                forest.mapSelections {
                    visits++
                    it
                }.single()
            )
            assertEquals(1, visits)
        }
    }

    @Test
    fun `direct mapping preserves duplicate occurrences across ordinary concatenated and object forests`() {
        val world = TestWorld.fromSDL("type Query { first: Int, second: Int }")
        val query = world.assumptions.schema.requireQueryTypeDef()
        val first = Selection.of(ObjectEngineResult.Key.of(query.requireField("first"), emptyMap()), setOf(query), selectionForestOf())
        val second = Selection.of(ObjectEngineResult.Key.of(query.requireField("second"), emptyMap()), setOf(query), selectionForestOf())
        val ordinary = selectionForestOf(first, first, second)
        val concatenated = selectionForestOf(first) + selectionForestOf(second, first)
        val normalized = selectionForestOf(first, second).merge(query)

        for (forest in listOf(ordinary, concatenated, normalized)) {
            var visits = 0
            val mapped = forest.mapSelections {
                visits++
                first
            }
            assertEquals(forest.size, visits)
            assertEquals(forest.size, mapped.size)
            assertTrue(mapped.all { it === first })
            val retained = forest.mapNotNullSelections { it.takeIf { it === first } }
            assertEquals(if (forest === normalized) 1 else 2, retained.size)
            assertTrue(retained.all { it === first })
        }
    }

    @Test
    fun `deep left associated concatenation preserves forest observations`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val selection =
            Selection.of(
                key = ObjectEngineResult.Key.of(query.requireField("value"), emptyMap()),
                possibleTypes = setOf(query),
                subselections = selectionForestOf(),
            )
        val count = 20_000
        var forest: SelectionForest = selectionForestOf()
        repeat(count) {
            forest += selectionForestOf(selection)
        }

        assertEquals(count, forest.size)
        assertFalse(forest.isEmpty())
        assertTrue(forest.all { it === selection })
        var visits = 0
        forest.forEach { visits++ }
        assertEquals(count, visits)
        assertEquals(count, forest.filter { it === selection }.size)
        assertEquals(count, forest.flatMap(::selectionForestOf).size)
        assertEquals(1, forest.merge(query).size)

        val empty = selectionForestOf()
        var sharedEmpty = empty
        repeat(10_000) {
            sharedEmpty += sharedEmpty
        }
        assertSame(empty, sharedEmpty)

        val singleton = sharedEmpty + selectionForestOf(selection) + sharedEmpty
        assertSame(selection, singleton.single())
    }

    @Test
    fun `concatenation prefers a leaf on the left and all short circuits`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val key = ObjectEngineResult.Key.of(query.requireField("value"), emptyMap())
        val previous = Selection.of(key, setOf(query), selectionForestOf())
        val added = Selection.of(key, setOf(query), selectionForestOf())
        var accumulated = selectionForestOf(previous)
        repeat(20_000) {
            accumulated += selectionForestOf(previous)
        }
        val leaf = selectionForestOf(added)

        // Ordinary forest order is unspecified; this checks the leaf-left optimization only.
        for (forest in listOf(accumulated + leaf, leaf + accumulated)) {
            var visits = 0
            assertFalse(
                forest.all { selection ->
                    visits++
                    assertSame(added, selection)
                    false
                },
            )
            assertEquals(1, visits)
            assertEquals(20_002, forest.size)
        }
        assertEquals(20_001, accumulated.size)
        assertSame(added, leaf.single())
    }

    @Test
    fun `branching concatenation preserves every shared occurrence`() {
        val world = TestWorld.fromSDL("type Query { first: Int, second: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val first = Selection.of(ObjectEngineResult.Key.of(query.requireField("first"), emptyMap()), setOf(query), selectionForestOf())
        val second = Selection.of(ObjectEngineResult.Key.of(query.requireField("second"), emptyMap()), setOf(query), selectionForestOf())
        val original = selectionForestOf(first) + selectionForestOf(second)
        var shared = original
        repeat(14) { shared += shared }
        val forest = selectionForestOf(first) + shared + selectionForestOf(second)

        var firstVisits = 0
        var secondVisits = 0
        forest.forEach { selection ->
            when {
                selection === first -> firstVisits++
                selection === second -> secondVisits++
                else -> error("Unexpected selection")
            }
        }
        assertEquals(16_385, firstVisits)
        assertEquals(16_385, secondVisits)
        assertEquals(32_770, forest.size)
        assertTrue(forest.all { it === first || it === second })
        assertEquals(firstVisits, forest.filter { it === first }.size)
        assertEquals(forest.size, forest.flatMap(::selectionForestOf).size)
        assertEquals(2, forest.merge(query).size)
        assertEquals(2, original.size)
    }

    @Test
    fun `deep concatenated left children are traversed iteratively`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val selection = Selection.of(ObjectEngineResult.Key.of(query.requireField("value"), emptyMap()), setOf(query), selectionForestOf())
        val chunk = selectionForestOf(selection) + selectionForestOf(selection)
        var forest = chunk
        repeat(20_000) { forest += chunk }

        assertEquals(40_002, forest.size)
        assertTrue(forest.all { it === selection })
        var visits = 0
        forest.forEach { visits++ }
        assertEquals(forest.size, visits)
        assertEquals(2, chunk.size)
    }

    @Test
    fun `all stops inside a leaf without visiting pending branches`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val selection = Selection.of(ObjectEngineResult.Key.of(query.requireField("value"), emptyMap()), setOf(query), selectionForestOf())
        val leaf = selectionForestOf(selection, selection, selection)
        val branch = leaf + leaf
        val forest = branch + branch
        var visits = 0

        assertFalse(
            forest.all {
                visits++
                false
            }
        )
        assertEquals(1, visits)
        assertEquals(12, forest.size)
    }

    @Test
    fun `concatenation rejects forests larger than the supported bound`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val query = requireNotNull(world.assumptions.schema.queryTypeDef)
        val selection =
            Selection.of(
                key = ObjectEngineResult.Key.of(query.requireField("value"), emptyMap()),
                possibleTypes = setOf(query),
                subselections = selectionForestOf(),
            )
        var forest = selectionForestOf()
        var powerOfTwo = selectionForestOf(selection)
        var remaining = SelectionForest.MAX_SIZE
        while (remaining != 0) {
            if (remaining and 1 != 0) forest += powerOfTwo
            remaining = remaining ushr 1
            if (remaining != 0) powerOfTwo += powerOfTwo
        }

        assertEquals(SelectionForest.MAX_SIZE, forest.size)
        assertThrows<IllegalArgumentException> { forest + selectionForestOf(selection) }
        assertThrows<IllegalArgumentException> {
            List(SelectionForest.MAX_SIZE + 1) { selection }.toSelectionForest()
        }
    }
}
