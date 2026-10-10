@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.testing.TestWorld

class InclusionConditionTest {
    private val schema =
        TestWorld.fromSDL("type Query { value: Boolean! }").schema
    private val field = schema.requireObjectField("Query", "value")
    private val x = Arguments.Variable.of(field, "x")
    private val y = Arguments.Variable.of(field, "y")

    @Test
    fun `always and never are identities and annihilators`() {
        val requirement = InclusionCondition.requires(mapOf(x to true))

        assertSame(requirement, InclusionCondition.Always.and(requirement))
        assertSame(requirement, requirement.and(InclusionCondition.Always))
        assertSame(InclusionCondition.Never, InclusionCondition.Never.and(requirement))
        assertSame(InclusionCondition.Never, requirement.and(InclusionCondition.Never))
        assertSame(InclusionCondition.Always, InclusionCondition.Always.or(requirement))
        assertSame(InclusionCondition.Always, requirement.or(InclusionCondition.Always))
        assertSame(requirement, InclusionCondition.Never.or(requirement))
        assertSame(requirement, requirement.or(InclusionCondition.Never))
    }

    @Test
    fun `conjunction combines requirements and rejects conflicts`() {
        val condition =
            InclusionCondition.requires(mapOf(x to true))
                .and(InclusionCondition.requires(mapOf(y to false)))

        assertTrue(condition.include(mapOf(x to true, y to false)))
        assertFalse(condition.include(mapOf(x to true, y to true)))
        assertSame(
            InclusionCondition.Never,
            condition.and(InclusionCondition.requires(mapOf(x to false))),
        )
    }

    @Test
    fun `disjunction includes when any alternative permits inclusion`() {
        val condition =
            InclusionCondition.anyOf(
                listOf(
                    InclusionCondition.requires(mapOf(x to true)),
                    InclusionCondition.requires(mapOf(y to false)),
                ),
            )

        assertTrue(condition.include(mapOf(x to true, y to true)))
        assertTrue(condition.include(mapOf(x to false, y to false)))
        assertFalse(condition.include(mapOf(x to false, y to true)))
    }

    @Test
    fun `nested and duplicate disjunctions preserve inclusion behavior`() {
        val xRequired = InclusionCondition.requires(mapOf(x to true))
        val yRequired = InclusionCondition.requires(mapOf(y to false))
        val condition =
            InclusionCondition.anyOf(
                listOf(
                    xRequired,
                    InclusionCondition.anyOf(listOf(xRequired, yRequired)),
                ),
            )

        assertEquals(setOf(x, y), condition.usedVariables())
        assertTrue(condition.include(mapOf(x to true, y to true)))
        assertTrue(condition.include(mapOf(x to false, y to false)))
        assertFalse(condition.include(mapOf(x to false, y to true)))
    }

    @Test
    fun `conjunction prunes contradicted disjunction branches without distribution`() {
        val condition =
            InclusionCondition.anyOf(
                listOf(
                    InclusionCondition.requires(mapOf(x to true)),
                    InclusionCondition.requires(mapOf(y to true)),
                ),
            ).and(InclusionCondition.requires(mapOf(y to false)))

        assertTrue(condition.include(mapOf(x to true, y to false)))
        assertFalse(condition.include(mapOf(x to false, y to false)))
        assertFalse(condition.include(mapOf(x to true, y to true)))
        assertEquals(1, condition.nodeCount())
    }

    @Test
    fun `mapping variables preserves conflicts introduced by the mapping`() {
        val condition =
            InclusionCondition.requires(mapOf(x to true, y to false))
                .mapVariables { x }

        assertSame(InclusionCondition.Never, condition)
    }

    @Test
    fun `mapping variables traverses every disjunction alternative`() {
        val condition =
            InclusionCondition.anyOf(
                listOf(
                    InclusionCondition.requires(mapOf(x to true)),
                    InclusionCondition.requires(mapOf(y to false)),
                ),
            ).mapVariables { variable -> if (variable == x) y else x }

        assertEquals(setOf(x, y), condition.usedVariables())
        assertTrue(condition.include(mapOf(x to true, y to true)))
        assertTrue(condition.include(mapOf(x to false, y to false)))
        assertFalse(condition.include(mapOf(x to true, y to false)))
    }

    @Test
    fun `suspending evaluation short circuits conditions`() =
        runBlocking {
            val visited = mutableListOf<Arguments.Variable>()
            val condition =
                InclusionCondition.anyOf(
                    listOf(
                        InclusionCondition.requires(mapOf(x to true)),
                        InclusionCondition.requires(mapOf(y to true)),
                    ),
                )

            assertTrue(
                condition.include { variable ->
                    visited += variable
                    variable == x
                },
            )
            assertEquals(listOf(x), visited)

            visited.clear()
            assertFalse(
                InclusionCondition.Never.include { variable ->
                    visited += variable
                    true
                },
            )
            assertTrue(visited.isEmpty())
        }

    @Test
    fun `condition graph grows linearly across Boolean products`() {
        val owner = viaduct.engine.runtime2.model.ResolverOccurrenceId.at(
            ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true),
            emptyList(),
        )
        var condition: InclusionCondition = InclusionCondition.Always
        repeat(100) { index ->
            val left = Arguments.Variable.of(field, "left$index").instantiate(owner)
            val right = Arguments.Variable.of(field, "right$index").instantiate(owner)
            condition =
                condition.and(
                    InclusionCondition.requires(mapOf(left to true))
                        .or(InclusionCondition.requires(mapOf(right to true))),
                )
        }

        assertTrue(condition.nodeCount() <= 400)
        assertEquals(200, condition.usedVariables().size)
    }

    @Test
    fun `wide condition operations are stack safe`() =
        runBlocking {
            val condition =
                InclusionCondition.anyOf(
                    (0 until 10_000).map { index ->
                        InclusionCondition.requires(mapOf(Arguments.Variable.of(field, "x$index") to true))
                    },
                )

            assertFalse(condition.includeWith { false })
            assertFalse(condition.include { false })
            assertEquals(10_000, condition.usedVariables().size)
            assertFalse(condition.include(condition.usedVariables().associateWith { false }))
            assertEquals(condition, condition.mapVariables { it })
        }
}
