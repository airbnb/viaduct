package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.schema.materializeSelectionsFrom
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class UnifiedSelectionForestTest {
    @Test
    fun `one ND graph supplies construction and alias-specific projections`() {
        val world = TestWorld.fromSDL("type Query { user(id: Int!): User } type User { name: String email: String }")
        val graph = world.schemas.operationSelectionsFrom("{ first: user(id: 1) { name } second: user(id: 1) { email } }")
        val query = world.schema.requireQueryTypeDef()
        val construction = graph.merge(query)
        val projection = graph.collectProjection(query)
        assertEquals(1, construction.size)
        assertEquals(2, construction.single().subselections.size)
        assertEquals(setOf("first", "second"), projection.responseKeys())
        assertEquals("name", projection["first"].subselections.single().key.field.name)
        assertEquals("email", projection["second"].subselections.single().key.field.name)
        assertSame(graph.filter { it.responseKey == "first" }.single().subselections, projection["first"].subselections)
        assertSame(projection, graph.collectProjection(query))
    }

    @Test
    fun `projection concatenation retains first response occurrence order`() {
        val world = TestWorld.fromSDL("type Query { value: Int }")
        val (_, first) = world.schemas.materializeSelectionsFrom("fragment F on Query { a: value }")
        val (_, second) = world.schemas.materializeSelectionsFrom("fragment F on Query { b: value }")
        assertEquals(listOf("a", "b"), (first + second).collectProjection(world.schema.requireQueryTypeDef()).responseKeys().toList())
        assertEquals(1, (first + second).merge(world.schema.requireQueryTypeDef()).size)
    }

    @Test
    fun `ND fragment templates share child graphs but retain independent projection roots`() {
        val world = TestWorld.fromSDL("type Query { user: User } type User { friend: User name: String }")
        val document = graphql.parser.Parser.parse("fragment Main on Query { first: user { ...Fields } second: user { ...Fields } } fragment Fields on User { friend { name } }")
        val parsed = viaduct.engine.runtime2.schema.NormalizedFragmentParser(world.schemas.graphQLSchema, world.schema, emptyMap()).parseFragment(document)
        val forest = parsed.fragment.subselections
        val projection = forest.collectProjection(world.schema.requireQueryTypeDef())
        assertNotSame(projection["first"], projection["second"])
        assertSame(projection["first"].subselections.single().subselections, projection["second"].subselections.single().subselections)
        assertEquals("name", projection["first"].subselections.single().subselections.single().key.field.name)
        assertSame(forest, parsed.fragment.subselections)
    }
}
