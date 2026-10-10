@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.testing.TestWorld

class InclusionConditionFailureOrderTest {
    private val field = TestWorld.fromSDL("type Query { value: Boolean }").schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")
    private val z = Arguments.Variable.of(field, "z")
    private val xRequired = InclusionCondition.requires(mapOf(x to true))
    private val yRequired = InclusionCondition.requires(mapOf(y to true))
    private val zRequired = InclusionCondition.requires(mapOf(z to true))

    @Test
    fun `equal guarded alternatives retain the first normalized requirement order`(): Unit =
        runBlocking {
            val condition = zRequired.and(xRequired).or(xRequired).and(zRequired)
            val visited = mutableListOf<Arguments.Variable>()

            fun binding(variable: Arguments.Variable): Boolean {
                visited += variable
                check(variable != x) { "Excluded demand read x" }
                return false
            }

            assertFalse(condition.includeWith(::binding))
            assertEquals(listOf(z), visited)
            visited.clear()
            assertFalse(condition.include { binding(it) })
            assertEquals(listOf(z), visited)
            assertFalse(condition.include(mapOf(z to false)))
        }

    @Test
    fun `equal guarded alternatives do not await the excluded binding`(): Unit =
        runBlocking {
            val condition = zRequired.and(xRequired).or(xRequired).and(zRequired)
            val unresolved = CompletableDeferred<Boolean>()
            assertFalse(withTimeout(1_000) { condition.include { if (it == z) false else unresolved.await() } })
        }

    @Test
    fun `a false right guard does not hide failures in other left alternatives`(): Unit =
        runBlocking {
            val condition = xRequired.or(yRequired).and(zRequired)
            val visited = mutableListOf<Arguments.Variable>()

            fun binding(variable: Arguments.Variable): Boolean {
                visited += variable
                return when (variable) {
                    x -> true
                    y -> error("Rejected alternative failed")
                    z -> false
                    else -> error("Unexpected variable")
                }
            }

            assertThrows(IllegalStateException::class.java) { condition.includeWith(::binding) }
            assertEquals(listOf(x, z, y), visited)
            visited.clear()
            try {
                condition.include { binding(it) }
                error("Missing rejected-alternative failure")
            } catch (cause: IllegalStateException) {
                assertEquals("Rejected alternative failed", cause.message)
            }
            assertEquals(listOf(x, z, y), visited)
        }

    @Test
    fun `successful complete alternatives still short circuit failed siblings`(): Unit =
        runBlocking {
            val condition = xRequired.or(yRequired).and(zRequired)
            assertTrue(condition.includeWith { if (it == y) error("Unneeded sibling") else true })
            val unresolved = CompletableDeferred<Boolean>()
            assertTrue(withTimeout(1_000) { condition.include { if (it == y) unresolved.await() else true } })
        }
}
