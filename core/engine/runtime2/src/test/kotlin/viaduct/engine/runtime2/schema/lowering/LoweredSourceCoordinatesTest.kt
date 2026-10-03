package viaduct.engine.runtime2.schema.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LoweredSourceCoordinatesTest {
    private val lowered = lowerSchema(graphQLSchema(SCHEMA))

    @Test
    fun `ordinary source field resolves to the same lowered coordinate`() {
        val field = lowered.loweredFieldFromSourceCoordinate("Query", "count")

        assertSame(lowered.requireField("Query", "count"), field)
        assertSame(field.type, lowered.sourceTypeExpr(field))
    }

    @Test
    fun `Node source field retains its source coordinate`() {
        val field = lowered.loweredFieldFromSourceCoordinate("Query", "users")

        assertSame(lowered.requireField("Query", "users"), field)
        assertSame(field.type, lowered.sourceTypeExpr(field))
    }

    @Test
    fun `typename resolves through its lowered owner`() {
        assertSame(
            lowered.requireField("User", LOWERED_TYPENAME_FIELD),
            lowered.loweredTypenameField("User"),
        )
        assertSame(
            lowered.requireField("Named", LOWERED_TYPENAME_FIELD),
            lowered.loweredFieldFromSourceCoordinate("Named", "__typename"),
        )
        assertSame(
            lowered.requireField(ALL_SOURCE_OBJECTS_TYPE, LOWERED_TYPENAME_FIELD),
            lowered.loweredFieldFromSourceCoordinate("SearchResult", "__typename"),
        )
    }

    @Test
    fun `synthetic coordinates are not accepted as source coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            lowered.loweredFieldFromSourceCoordinate(ALL_SOURCE_OBJECTS_TYPE, "__typename")
        }
        assertFailsWith<IllegalArgumentException> {
            lowered.loweredFieldFromSourceCoordinate("Query", LOWERED_TYPENAME_FIELD)
        }
    }

    @Test
    fun `Node source type preserves every wrapper`() {
        val field = lowered.requireField("Query", "users")
        val sourceType = lowered.sourceTypeExpr(field)

        assertSame(lowered.requireType("User"), sourceType.baseTypeDef)
        assertEquals(field.type.nullabilityShape(), sourceType.nullabilityShape())
        assertEquals(2, sourceType.listDepth)
    }

    private companion object {
        val SCHEMA =
            """
            interface Node {
              id: ID!
            }

            interface Named {
              name: String!
            }

            union SearchResult = User | Photo

            type User implements Node & Named {
              id: ID!
              name: String!
            }

            type Photo implements Named {
              name: String!
            }

            type Query {
              count: Int!
              users: [[User!]!]
              search: SearchResult
            }
            """
    }
}
