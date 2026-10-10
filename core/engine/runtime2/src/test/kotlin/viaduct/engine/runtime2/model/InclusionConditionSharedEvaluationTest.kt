@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.testing.TestWorld

class InclusionConditionSharedEvaluationTest {
    private val field = TestWorld.fromSDL("type Query { value: Boolean }").schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")

    @Test
    fun `rejecting a shared product inspects remaining alternatives without expanding them`(): Unit =
        runBlocking {
            val guard = Arguments.Variable.of(field, "guard")
            for (depth in listOf(12, 64, 256)) {
                val condition = sharedPrefix(depth).and(InclusionCondition.requires(mapOf(guard to true)))
                for (suspending in listOf(false, true)) {
                    var reads = 0

                    fun binding(variable: Arguments.Variable): Boolean {
                        assertTrue(++reads <= condition.nodeCount())
                        return variable != guard
                    }
                    val included = if (suspending) {
                        condition.include {
                            yield()
                            binding(it)
                        }
                    } else {
                        condition.includeWith(::binding)
                    }
                    assertFalse(included)
                    assertEquals(2 * depth + 3, reads)
                }
            }
        }

    @Test
    fun `ordinary evaluation visits shared prefixes once per invocation`() {
        for (depth in listOf(12, 64, 256)) {
            val condition = sharedPrefix(depth)
            val limit = 2 * condition.nodeCount()
            var reads = 0
            assertFalse(
                condition.includeWith {
                    assertTrue(++reads <= limit)
                    false
                }
            )
            assertEquals(2, reads)

            reads = 0
            assertTrue(
                condition.includeWith {
                    assertTrue(++reads <= limit)
                    true
                }
            )
            assertEquals(depth + 1, reads)
            assertFalse(condition.include(mapOf(x to false, y to false)))
            assertFalse(condition.includeIfBound(emptyMap()))
        }
    }

    @Test
    fun `suspending evaluation visits shared prefixes once per invocation`(): Unit =
        runBlocking {
            for (depth in listOf(12, 64, 256)) {
                val condition = sharedPrefix(depth)
                val limit = 2 * condition.nodeCount()
                var reads = 0
                assertFalse(
                    condition.include {
                        assertTrue(++reads <= limit)
                        yield()
                        false
                    }
                )
                assertEquals(2, reads)

                reads = 0
                assertTrue(
                    condition.include {
                        assertTrue(++reads <= limit)
                        yield()
                        true
                    }
                )
                assertEquals(depth + 1, reads)
            }
        }

    private fun sharedPrefix(depth: Int): InclusionCondition {
        var condition = InclusionCondition.requires(mapOf(x to true)).or(InclusionCondition.requires(mapOf(y to true)))
        repeat(depth) { index ->
            condition = condition.and(InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "a$index") to true)))
                .or(condition.and(InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "b$index") to true))))
        }
        assertEquals(5 * depth + 3, condition.nodeCount())
        return condition
    }
}
