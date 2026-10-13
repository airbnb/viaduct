package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.graphql.schema.ViaductSchema

class MaterializeSelectionLayoutTest {
    @Test
    fun `reusable layouts keep response aliases and concrete type alternatives`() {
        val fixture = TestWorld.fromSDL("type Query { node: Node } interface Node { id: ID } type A implements Node { id: ID a: String } type B implements Node { id: ID b: String }")
        val source = fixture.schemas.fragmentFrom("fragment Input on Node { ... on A { result: a } ... on B { result: b } }").materializeSelections
        val a = fixture.schema.requireType("A") as ViaductSchema.Object
        val b = fixture.schema.requireType("B") as ViaductSchema.Object
        val aLayout = source.collect(a)
        val bLayout = source.collect(b)
        assertEquals(setOf("result"), aLayout.responseKeys())
        assertEquals("a", aLayout["result"].key.field.name)
        assertEquals("b", bLayout["result"].key.field.name)
        assertSame(aLayout, source.collect(a))
        assertSame(bLayout, source.collect(b))
        assertNotSame(aLayout, bLayout)
    }
}
