package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

/** Object shape is sealed independently of whether its values came from source or resolver work. */
interface FrozenObjectResolutionContract : ResolverContract {
    @Test
    fun `seals active and passive object occurrences including list elements`() {
        val fixture = TestWorld.fromDSL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = """
                extend type Query {
                  root: Root! @resolver(result: {passive: {value: 1}, items: [[{}]]})
                  unused: Int
                }
                type Root {
                  passive: Passive!
                  items: [[Active!]!]!
                  unused: Int
                }
                type Passive {
                  value: Int!
                  unused: Int
                }
                type Active {
                  value: Int! @resolver(result: 2)
                  unused: Int
                }
            """.trimIndent(),
        )
        val world = fixture.assumptions
        val query = resolveAndValidate(fixture, "{ root { passive { value } items { value } } }")
        val root = assertIs<ObjectEngineResult>(query.getCell(world.schema.contractKey("Query", "root")).get())
        val passive = assertIs<ObjectEngineResult>(root.getCell(world.schema.contractKey("Root", "passive")).get())
        val outer = assertIs<ListEngineResult>(root.getCell(world.schema.contractKey("Root", "items")).get())
        val inner = assertIs<ListEngineResult>(outer[0].get())
        val active = assertIs<ObjectEngineResult>(inner[0].get())
        assertEquals(1, passive.getCell(world.schema.contractKey("Passive", "value")).get())
        assertEquals(2, active.getCell(world.schema.contractKey("Active", "value")).get())
        listOf(query, root, passive, active).forEach { result ->
            val extra = ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField(result.type.name, "unused"),
                emptyMap(),
            )
            assertFailsWith<NoSuchElementException> { result.reserveCell(extra) }
        }
    }
}
