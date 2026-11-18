package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.registry.instantiateVariables
import viaduct.engine.runtime2.model.registry.partitionVariableDemand
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class ConstructionSelectionProjectionTest {
    private val world = TestWorld.fromSDL(
        "directive @policy on FIELD type Query { checked: Int value(id: Int): Int child: Child } type Child { value(id: Int): Int }",
    )
    private val query = world.schema.requireQueryTypeDef()

    @Test
    fun `every merge path clears projection metadata without changing the source`() {
        for (fields in listOf(
            "first: value @policy",
            "first: child @policy { nested: value }",
            "first: value(id: 1) @policy second: value(id: 2)",
            "first: value(id: 1) @policy second: value(id: 1)",
            "first: child @policy { a: value } second: child { b: value }",
        )) {
            val source = world.schemas.fragmentFrom("fragment Input on Query { $fields }").subselections
            val before = source.collect(query)
            val construction = source.merge(query)
            assertConstructionOnly(construction)
            assertSame(before, source.collect(query))
            assertTrue(source.all { it.responseKey != null })
            assertTrue(requireNotNull(source.filter { it.responseKey == "first" }.single().fieldDirectives).hasDirective("policy"))
            assertConstructionOnly(listOf(construction, construction).flatMerge(query))
        }
    }

    @Test
    fun `clearing projection metadata shares children and reuses construction nodes`() {
        val source = world.schemas.fragmentFrom("fragment Input on Query { alias: child @policy { nested: value } }").subselections
        val construction = source.merge(query)
        assertSame(source.single().subselections, construction.single().subselections)
        assertSame(construction, source.merge(query))
        assertSame(construction.single(), construction.merge(query).single())
        assertSame(construction.single(), listOf(construction).flatMerge(query).single())
        assertConstructionOnly(ObjectSelectionForest.of(query, listOf(source.single() as ObjectSelection)))
    }

    @Test
    fun `construction status survives filtering mapping guarding and concatenation`() {
        val source = world.schemas.fragmentFrom("fragment Input on Query { alias: value }").subselections
        val construction = source.merge(query)
        for (transformed in listOf(
            construction.filter { true },
            construction.mapSelections { it },
            construction.flatMap(::selectionForestOf),
            construction.guardedBy(InclusionCondition.Never),
            construction + source,
            source + construction,
        )) {
            assertFailsWith<IllegalArgumentException> { transformed.collect(query) }
        }
        assertEquals(setOf("alias"), source.collect(query).responseKeys())
    }

    @Test
    fun `variable partitioning and instantiation keep demand nonprojectable`() {
        val source = world.schemas.fragmentFrom(
            "fragment Input on Query { stable: value(id: 1) dynamic: value(id: ${'$'}v) parent: child { static: value(id: 1) dynamic: value(id: ${'$'}v) } }",
            variableTarget = ResolverTarget.FieldValueResolverTarget(world.schema.requireObjectField("Query", "checked")),
        ).subselections
        val occurrence = ResolverOccurrenceId.at(ObjectEngineResult.of(query), emptyList())
        for (input in listOf(source, source.merge(query))) {
            val (static, dynamic) = input.partitionVariableDemand()
            assertTrue(!static.isEmpty() && !dynamic.isEmpty())
            assertConstructionOnly(static)
            assertConstructionOnly(dynamic)
            assertConstructionOnly(dynamic.instantiateVariables(occurrence))
        }
        val (static, dynamic) = source.merge(query).partitionVariableDemand()
        assertConstructionOnly(listOf(static, dynamic).flatMerge(query))
        assertTrue(source.instantiateVariables(occurrence).all { it.responseKey != null })
    }

    @Test
    fun `construction selections have no default response key or directives`() {
        val source = world.schemas.fragmentFrom("fragment Input on Query { value @policy }").subselections.single()
        assertEquals("value", source.responseKey)
        val construction = Selection.of(source.key, source.possibleTypes, source.subselections)
        assertNull(construction.responseKey)
        assertNull(construction.fieldDirectives)
        assertFailsWith<IllegalArgumentException> {
            Selection.of(source.key, source.possibleTypes, source.subselections, fieldDirectives = source.fieldDirectives)
        }
        assertFailsWith<IllegalArgumentException> {
            Selection.of(source.key, source.possibleTypes, source.subselections, responseKey = "")
        }
        // Applicability cannot hide a construction-only occurrence from the projection boundary.
        assertFailsWith<IllegalArgumentException> {
            selectionForestOf(Selection.of(source.key, emptySet(), source.subselections)).collect(query)
        }
    }

    private fun assertConstructionOnly(forest: SelectionForest) {
        assertTrue(!forest.isEmpty())
        forest.forEach {
            assertNull(it.responseKey)
            assertNull(it.fieldDirectives)
        }
        val error = assertFailsWith<IllegalArgumentException> { forest.collect(query) }
        assertTrue(assertNotNull(error.message).contains("construction-only"))
    }
}
