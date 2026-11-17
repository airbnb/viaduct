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
    fun `prepares enum values outside the internal namespace`() {
        val source =
            graphQLSchema(
                """
                type Query { status: Status }
                enum Status {
                  SYSTEM_AV_ALT_PLAY
                  AOV_AND_PASSWORD_RESET
                  V_ALPHA
                  AV_A
                  V_A
                  V_A_typename
                  user_V_A_node
                  user_viaduct_internal__node
                  _viaduct_internal_node
                }
                """,
            )

        val result = runCatching { ViaductAndGJSchema.fromGraphQLSchema(source) }

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val status = result.getOrThrow().loweredSchema.requireType("Status") as ViaductSchema.Enum
        assertEquals(
            setOf(
                "SYSTEM_AV_ALT_PLAY",
                "AOV_AND_PASSWORD_RESET",
                "V_ALPHA",
                "AV_A",
                "V_A",
                "V_A_typename",
                "user_V_A_node",
                "user_viaduct_internal__node",
                "_viaduct_internal_node",
            ),
            status.values.map { it.name }.toSet(),
        )
    }

    @Test
    fun `rejects enum values starting with the reserved prefix`() {
        listOf("_viaduct_internal__", "_viaduct_internal__typename", "_viaduct_internal__VALUE").forEach { name ->
            val source =
                graphQLSchema(
                    """
                    type Query { status: Status }
                    enum Status { $name }
                    """,
                )

            val exception =
                assertFailsWith<IllegalArgumentException>(name) {
                    ViaductAndGJSchema.fromGraphQLSchema(source)
                }

            assertTrue(exception.message.orEmpty().contains("reserved prefix _viaduct_internal__"), name)
        }
    }

    @Test
    fun `rejects reserved names through the public preparation entry point`() {
        listOf(
            "type Query { _viaduct_internal__typename: String }",
            "type Query { value: Int } type _viaduct_internal__AllSourceObjects { value: String }",
            "type Query { value: Int } directive @_viaduct_internal__example on FIELD_DEFINITION",
            "type Query { value(_viaduct_internal__arg: Int): Int }",
            "type Query { value: Int } directive @example(_viaduct_internal__arg: Int) on FIELD_DEFINITION",
        ).forEach { sdl ->
            val source = graphQLSchema(sdl)

            val exception =
                assertFailsWith<IllegalArgumentException>(sdl) {
                    ViaductAndGJSchema.fromGraphQLSchema(source)
                }

            assertTrue(exception.message.orEmpty().contains("reserved prefix _viaduct_internal__"), sdl)
        }
    }
}
