package model.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import model.lowering.sourceCompositeType
import model.requireType
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelation

class GraphQLTypeRelationsParityTest {
    private val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas

    @Test
    fun `delegates source type relations to shared GraphQL relations`() {
        assertEquals(GraphQLTypeRelation.Same, relationBetween("Left", "Left"))
        assertEquals(GraphQLTypeRelation.WiderThan, relationBetween("Left", "Both"))
        assertEquals(GraphQLTypeRelation.NarrowerThan, relationBetween("Both", "Left"))
        assertEquals(GraphQLTypeRelation.WiderThan, relationBetween("Mixed", "Other"))
        assertEquals(GraphQLTypeRelation.Coparent, relationBetween("Left", "Right"))
        assertEquals(GraphQLTypeRelation.Coparent, relationBetween("Mixed", "Left"))
        assertEquals(GraphQLTypeRelation.None, relationBetween("LeftOnly", "Other"))

        assertEquals(
            GraphQLTypeRelation.WiderThan,
            relationBetween("EmptyParent", "EmptyChild"),
        )
        assertFalse(
            schema.typeRelations.isSpreadable(
                sourceType("EmptyParent"),
                sourceType("EmptyChild"),
            ),
        )

        assertEquals(GraphQLTypeRelation.WiderThan, relationBetween("Grand", "Deep"))
        assertEquals(GraphQLTypeRelation.WiderThan, relationBetween("Parent", "Deep"))
        assertEquals(GraphQLTypeRelation.WiderThan, relationBetween("Child", "Deep"))
    }

    @Test
    fun `populates model possible types from shared GraphQL relations`() {
        val both = schema.loweredSchema.requireType("Both") as ViaductSchema.Object
        val leftOnly = schema.loweredSchema.requireType("LeftOnly") as ViaductSchema.Object
        val other = schema.loweredSchema.requireType("Other") as ViaductSchema.Object
        val deep = schema.loweredSchema.requireType("Deep") as ViaductSchema.Object

        assertEquals(setOf(both, leftOnly), compositeType("Left").possibleObjectTypes)
        assertEquals(setOf(both), compositeType("Right").possibleObjectTypes)
        assertEquals(setOf(both, other), compositeType("Mixed").possibleObjectTypes)
        assertEquals(emptySet(), compositeType("EmptyParent").possibleObjectTypes)
        assertEquals(emptySet(), compositeType("EmptyChild").possibleObjectTypes)
        assertEquals(setOf(deep), compositeType("Grand").possibleObjectTypes)
        assertEquals(setOf(deep), compositeType("Parent").possibleObjectTypes)
        assertEquals(setOf(deep), compositeType("Child").possibleObjectTypes)
    }

    private fun relationBetween(
        first: String,
        second: String,
    ): GraphQLTypeRelation.Relation =
        schema.typeRelations.relationUnwrapped(
            sourceType(first),
            sourceType(second),
        )

    private fun sourceType(typeName: String) = schema.sourceCompositeType(compositeType(typeName))

    private fun compositeType(typeName: String): ViaductSchema.CompositeTypeDef = schema.loweredSchema.requireType(typeName) as ViaductSchema.CompositeTypeDef

    private companion object {
        val SCHEMA_SDL =
            """
            interface Left {
              label: String
            }

            interface Right {
              code: Int
            }

            type Both implements Left & Right {
              label: String
              code: Int
            }

            type LeftOnly implements Left {
              label: String
            }

            type Other {
              value: Boolean
            }

            union Mixed = Both | Other

            interface EmptyParent {
              empty: String
            }

            interface EmptyChild implements EmptyParent {
              empty: String
            }

            interface Grand {
              id: ID!
            }

            interface Parent implements Grand {
              id: ID!
            }

            interface Child implements Parent & Grand {
              id: ID!
            }

            type Deep implements Child & Parent & Grand {
              id: ID!
            }

            type Query {
              left: Left
              right: Right
              mixed: Mixed
              empty: EmptyParent
              deep: Grand
            }
            """.trimIndent()
    }
}
