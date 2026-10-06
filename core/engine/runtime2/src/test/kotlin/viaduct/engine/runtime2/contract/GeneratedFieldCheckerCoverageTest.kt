package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class GeneratedFieldCheckerCoverageTest {
    private val world = TestWorld.fromSDL(
        """
        type Query {
          first: Result!
          second: Entity
        }

        union Result = Item

        interface Entity {
          value: Int!
        }

        type Item implements Entity {
          value: Int!
          other: Int!
        }
        """.trimIndent(),
    ).schemas

    @Test
    fun `recognizes repeated concrete coordinates through different abstract object paths`() {
        val selections = world.fragmentFrom(
            """
            fragment Generated on Query {
              first { ... on Item { value } }
              second { value }
            }
            """.trimIndent(),
        ).subselections

        assertEquals(setOf(FieldCoordinate("Item", "value")), selections.repeatedSelectedFieldCoordinates())
    }

    @Test
    fun `different fields on the same returned type do not imply a repeated coordinate`() {
        val selections = world.fragmentFrom(
            """
            fragment Generated on Query {
              first { ... on Item { other } }
              second { value }
            }
            """.trimIndent(),
        ).subselections

        assertEquals(emptySet(), selections.repeatedSelectedFieldCoordinates())
    }

    @Test
    fun `one abstract field selection does not imply repetition`() {
        val selections = world.fragmentFrom(
            "fragment Generated on Query { second { value } }",
        ).subselections

        assertEquals(emptySet(), selections.repeatedSelectedFieldCoordinates())
    }
}
