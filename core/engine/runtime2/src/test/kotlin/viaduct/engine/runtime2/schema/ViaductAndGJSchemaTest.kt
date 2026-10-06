package viaduct.engine.runtime2.schema

import graphql.schema.GraphQLCompositeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD
import viaduct.engine.runtime2.schema.lowering.graphQLSchema
import viaduct.engine.runtime2.schema.lowering.requireField
import viaduct.engine.runtime2.schema.lowering.requireType
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef

class ViaductAndGJSchemaTest {
    @Test
    fun `prepares related source and canonical schemas without modifying the source`() {
        val source =
            graphQLSchema(
                """
                type Query { pet: Pet }
                interface Pet { name: String }
                type Cat implements Pet { name: String }
                type Dog implements Pet { name: String }
                """,
            )

        val schemas = ViaductAndGJSchema.fromGraphQLSchema(source)

        assertSame(source, schemas.graphQLSchema)
        val pet = source.getType("Pet") as GraphQLCompositeType
        val possibleTypes = schemas.typeRelations.possibleObjectTypes(pet).associateBy { it.name }
        assertEquals(setOf("Cat", "Dog"), possibleTypes.keys)
        possibleTypes.forEach { (name, type) ->
            assertSame(source.getObjectType(name), type)
            val loweredType = schemas.loweredSchema.requireType(name) as ViaductSchema.Object
            assertSame(type, loweredType.gjDef)
            assertSame(loweredType, loweredType.field("name")!!.containingDef)
            assertTrue(loweredType.field(LOWERED_TYPENAME_FIELD) != null)
            assertNull(type.getFieldDefinition(LOWERED_TYPENAME_FIELD))
        }
        assertSame(
            schemas.loweredSchema.requireType("Pet"),
            schemas.loweredSchema.requireField("Query", "pet").type.baseTypeDef,
        )
    }

    @Test
    fun `rejects reserved names through the public preparation entry point`() {
        val source = graphQLSchema("type Query { V_A_typename: String }")

        val exception =
            assertFailsWith<IllegalArgumentException> {
                ViaductAndGJSchema.fromGraphQLSchema(source)
            }

        assertTrue(exception.message.orEmpty().contains("reserved token V_A"))
        assertTrue(exception.message.orEmpty().contains("V_A_typename"))
    }
}
