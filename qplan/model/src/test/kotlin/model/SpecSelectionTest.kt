package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import model.spec.SpecSelection
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class SpecSelectionTest {
    @Test
    fun `field factory enforces subselection shape from the canonical field`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val nameField = schema.requireField("User", "name")
        val userField = schema.requireField("Query", "user")
        val nameSelection =
            SpecSelection.Field.of(
                alias = null,
                field = nameField,
                arguments = emptyMap(),
                subselections = null,
            )

        val userSelection =
            SpecSelection.Field.of(
                alias = null,
                field = userField,
                arguments = emptyMap(),
                subselections = listOf(nameSelection),
            )
        assertEquals("user", userSelection.fieldName)
        assertEquals(listOf(nameSelection), userSelection.subselections)

        assertFailsWith<IllegalArgumentException> {
            SpecSelection.Field.of(
                alias = null,
                field = nameField,
                arguments = emptyMap(),
                subselections = listOf(nameSelection),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpecSelection.Field.of(
                alias = null,
                field = userField,
                arguments = emptyMap(),
                subselections = null,
            )
        }
        val emptyUserSelection =
            SpecSelection.Field.of(
                alias = null,
                field = userField,
                arguments = emptyMap(),
                subselections = emptyList(),
            )
        assertEquals(emptyList(), emptyUserSelection.subselections)
    }

    @Test
    fun `inline fragment factory requires a non-empty selection set`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        assertFailsWith<IllegalArgumentException> {
            SpecSelection.InlineFragment.of(
                typeCondition = schema.requireType("User") as ViaductSchema.Object,
                selections = emptyList(),
            )
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type User {
              name: String!
            }

            type Query {
              user: User
            }
            """.trimIndent()
    }
}
