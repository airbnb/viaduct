@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.testing.TestWorld

class InclusionConditionContradictionTest {
    private val field = TestWorld.fromSDL("type Query { value: Boolean }").schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")
    private val z = Arguments.Variable.of(field, "z")
    private val w = Arguments.Variable.of(field, "w")
    private val xRequired = InclusionCondition.requires(mapOf(x to true))
    private val yRequired = InclusionCondition.requires(mapOf(y to true))
    private val zRequired = InclusionCondition.requires(mapOf(z to true))
    private val wRequired = InclusionCondition.requires(mapOf(w to true))
    private val guard = InclusionCondition.requires(mapOf(x to false))

    @Test
    fun `ordinary evaluators exclude nested contradictions before reading bindings`(): Unit =
        runBlocking {
            val alternatives = xRequired.or(yRequired).or(zRequired)
            val condition = alternatives.and(guard)
            assertFalse(condition.include(mapOf(y to false, z to false)))
            assertFalse(condition.includeIfBound(mapOf(y to false, z to false)))
            assertFalse(
                condition.includeWith { variable ->
                    check(variable != x) { "Contradictory alternative read x" }
                    false
                }
            )
            assertFalse(
                condition.include { variable ->
                    check(variable != x) { "Contradictory alternative read x" }
                    false
                }
            )
        }

    @Test
    fun `pruning retains the original left guard binding order`() {
        for (alternatives in listOf(xRequired.or(yRequired), xRequired.or(yRequired).or(zRequired))) {
            val condition = guard.and(alternatives)
            val visited = mutableListOf<Arguments.Variable>()
            assertThrows(IllegalStateException::class.java) {
                condition.includeWith { variable ->
                    visited += variable
                    if (variable == x) error("Required left guard failed") else false
                }
            }
            assertEquals(listOf(x), visited)
        }
    }

    @Test
    fun `nested pruning agrees with independent Boolean formulas`(): Unit =
        runBlocking {
            val shapes = listOf(
                xRequired.or(yRequired).or(zRequired),
                yRequired.or(zRequired.or(xRequired)),
                xRequired.and(wRequired).or(yRequired).or(zRequired),
                xRequired.or(yRequired).or(zRequired).and(wRequired.or(yRequired)),
            )
            for (bits in 0 until 16) {
                val bindings = listOf(x, y, z, w).mapIndexed { index, variable -> variable to (bits and (1 shl index) != 0) }.toMap()
                val xv = bindings.getValue(x)
                val yv = bindings.getValue(y)
                val zv = bindings.getValue(z)
                val wv = bindings.getValue(w)
                val expected = listOf(xv || yv || zv, yv || zv || xv, (xv && wv) || yv || zv, (xv || yv || zv) && (wv || yv))
                shapes.forEachIndexed { index, shape ->
                    for (condition in listOf(shape.and(guard), guard.and(shape))) {
                        assertEquals(expected[index] && !xv, condition.include(bindings), "shape=$index bits=$bits")
                        assertEquals(expected[index] && !xv, condition.include { bindings.getValue(it) })
                    }
                    val multiGuard = InclusionCondition.requires(mapOf(x to false, w to false))
                    assertEquals(expected[index] && !xv && !wv, shape.and(multiGuard).include(bindings))
                }
            }
        }

    @Test
    fun `pruning shared prefixes preserves compact sharing`() {
        var condition = xRequired.or(yRequired)
        repeat(256) { index ->
            condition = condition.and(InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "a$index") to true)))
                .or(condition.and(InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "b$index") to true))))
        }
        assertSame(condition, condition.pruneContradictions())
        val guarded = condition.and(guard)
        val pruned = guarded.pruneContradictions()
        assertTrue(pruned.nodeCount() <= guarded.nodeCount())
        var reads = 0
        assertFalse(
            pruned.includeWith { variable ->
                check(variable != x)
                reads++
                false
            }
        )
        // The first two branches each retain their own merged y requirement.
        assertEquals(2, reads)
    }

    @Test
    fun `guards differing only in nonconflicting requirements share pruning work`() {
        for (depth in listOf(64, 128, 256, 512, 1024)) {
            var requirementScans = 0

            fun countedRequires(values: Map<Arguments.Variable, Boolean>): InclusionCondition =
                InclusionCondition.Requires(
                    object : AbstractMap<Arguments.Variable, Boolean>() {
                        override val entries: Set<Map.Entry<Arguments.Variable, Boolean>>
                            get() {
                                requirementScans++
                                return values.entries
                            }
                    }
                )

            var condition = countedRequires(mapOf(x to false))
                .or(countedRequires(mapOf(y to true)))
                .or(countedRequires(mapOf(z to true)))
            repeat(depth) { index ->
                condition = condition.and(countedRequires(mapOf(x to true, Arguments.Variable.of(field, "a$index") to true)))
            }
            assertEquals(2 * depth + 5, condition.nodeCount())
            requirementScans = 0
            val pruned = condition.pruneContradictions()
            assertTrue(requirementScans <= 20 * depth, "depth=$depth requirementScans=$requirementScans")
            assertTrue(pruned.includeWith { true })
            assertFalse(pruned.includeWith { it != x })
            assertFalse(pruned.includeWith { it != Arguments.Variable.of(field, "a${depth - 1}") })
        }
    }

    @Test
    fun `pruning keeps opposite conflicting guards in separate caches`() {
        val alternatives = xRequired.or(yRequired).or(zRequired)
        val positive = InclusionCondition.requires(mapOf(x to true, w to true))
        val negative = InclusionCondition.requires(mapOf(x to false, w to true))
        val condition = alternatives.and(positive).or(alternatives.and(negative))
        val pruned = condition.pruneContradictions()
        for (bits in 0 until 16) {
            val bindings = listOf(x, y, z, w).mapIndexed { index, variable -> variable to (bits and (1 shl index) != 0) }.toMap()
            val expected = (bindings.getValue(x) || bindings.getValue(y) || bindings.getValue(z)) && bindings.getValue(w)
            assertEquals(expected, pruned.include(bindings), "bits=$bits")
        }
    }

    @Test
    fun `wide all-contradictory alternatives are pruned without recursion`() {
        val alternatives = InclusionCondition.anyOf(
            (0 until 10_000).map { index ->
                InclusionCondition.requires(mapOf(x to true, Arguments.Variable.of(field, "a$index") to true))
            }
        )
        assertSame(InclusionCondition.Never, alternatives.and(guard).pruneContradictions())
    }
}
