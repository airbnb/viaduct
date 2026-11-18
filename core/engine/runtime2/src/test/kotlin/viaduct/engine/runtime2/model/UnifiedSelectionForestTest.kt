package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class UnifiedSelectionForestTest {
    @Test
    fun `one source forest supplies construction and alias-specific projections`() {
        val world = TestWorld.fromSDL("type Query { user(id: Int!): User } type User { name: String email: String }")
        val source = world.schemas.operationSelectionsFrom("{ first: user(id: 1) { name } second: user(id: 1) { email } }")
        val query = world.schema.requireQueryTypeDef()
        val construction = source.merge(query)
        val projection = source.collect(query)
        assertEquals(1, construction.size)
        assertEquals(
            setOf("name", "email"),
            construction.single().subselections.merge(world.schema.requireType("User") as viaduct.graphql.schema.ViaductSchema.Object).keys().map { it.field.name }.toSet()
        )
        assertEquals(listOf("first", "second"), projection.responseKeys().toList())
        assertEquals("name", projection["first"].subselections.single().key.field.name)
        assertEquals("email", projection["second"].subselections.single().key.field.name)
        assertSame(source.filter { it.responseKey == "first" }.single().subselections, projection["first"].subselections)
        assertSame(projection, source.collect(query))
    }

    @Test
    fun `independent projection roots can use one response key for different invocations`() {
        val world = TestWorld.fromSDL("type Query { value(id: Int!): Int }")
        val first = world.schemas.fragmentFrom("fragment F on Query { answer: value(id: 1) }").subselections
        val second = world.schemas.fragmentFrom("fragment F on Query { answer: value(id: 2) }").subselections
        val query = world.schema.requireQueryTypeDef()
        assertEquals(2, (first + second).merge(query).size)
        assertEquals(first.single().key, first.collect(query)["answer"].key)
        assertEquals(second.single().key, second.collect(query)["answer"].key)
        assertFailsWith<IllegalArgumentException> { (first + second).collect(query) }
    }

    @Test
    fun `guarding and concatenating selections preserves response metadata and order`() {
        val world = TestWorld.fromSDL("directive @policy on FIELD type Query { value: Int }")
        val first = world.schemas.fragmentFrom("fragment F on Query { first: value @policy }").subselections
        val second = world.schemas.fragmentFrom("fragment F on Query { second: value }").subselections
        val guarded = first.guardedBy(InclusionCondition.Never).single()
        assertEquals("first", guarded.responseKey)
        assertTrue(requireNotNull(guarded.fieldDirectives).hasDirective("policy"))
        assertEquals(InclusionCondition.Never, guarded.inclusionCondition)
        assertEquals(listOf("first", "second"), (first + second).collect(world.schema.requireQueryTypeDef()).responseKeys().toList())
        assertEquals(1, (first + second).merge(world.schema.requireQueryTypeDef()).size)
        assertSame(first, Fragment.of(world.schema.requireQueryTypeDef(), first).subselections)
    }
}
