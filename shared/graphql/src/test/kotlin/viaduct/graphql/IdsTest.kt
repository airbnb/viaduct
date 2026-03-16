package viaduct.graphql

import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLInputObjectType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.utils.DefaultSchemaFactory

class IdsTest {
    private val sdl =
        """
            | type TestUser implements Node {
            |   id: ID!
            |   id2: ID!
            |   id3: [ID] @idOf(type: "TestUser")
            |   id4: [ID]
            | }
            |
            | input InputWithGlobalIDs {
            |   id: ID!
            |   id2: ID! @idOf(type: "O1")
            |   id3: [ID] @idOf(type: "O1")
            |   ids: [[ID]!] @idOf(type: "O2")
            | }
            |
            | type ObjectWithGlobalIds {
            |   # this field is named "id" so that it looks like a Node.id field,
            |   # though the containing type intentionally does not implement Node.
            |   id: ID!
            |
            |   id1: ID @idOf(type: "TestUser")
            |   id2: ID! @idOf(type: "TestUser")
            |   id4: [ID] @idOf(type: "TestUser")
            |   id5: [[ID!]] @idOf(type: "TestUser")
            |
            |   id6: ID
            |   id7: ID!
            |   id8: [ID]
            |   id9: [[ID!]]
            | }
        """.trimMargin()

    private val schema = let {
        val tdr = SchemaParser().parse(sdl)
        DefaultSchemaFactory.addDefaults(tdr)
        SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }

    @Test
    fun `isGlobalID -- true for id field of concrete Node type`() {
        assertTrue(
            isGlobalID(
                field = schema.getFieldDefinition(FieldCoordinates.coordinates("TestUser", "id")),
                parentType = schema.getObjectType("TestUser")
            )
        )
    }

    @Test
    fun `isGlobalID -- ID-typed output fields`() {
        val testUser = schema.getObjectType("TestUser")

        // field type is `ID!` but the field definition does not apply @idOf
        assertFalse(
            isGlobalID(testUser.getField("id2"), testUser)
        )

        // field type is `[ID]` and applies @idOf
        assertTrue(
            isGlobalID(testUser.getField("id3"), testUser)
        )

        // field type is [ID] and does not apply @idOf
        assertFalse(
            isGlobalID(testUser.getField("id4"), testUser)
        )
    }

    @Test
    fun `hasIdOfDirective -- true for field with @idOf`() {
        val testUser = schema.getObjectType("TestUser")
        assertTrue(testUser.getField("id3").hasIdOfDirective)
    }

    @Test
    fun `hasIdOfDirective -- false for field without @idOf`() {
        val testUser = schema.getObjectType("TestUser")
        assertFalse(testUser.getField("id").hasIdOfDirective)
    }

    @Test
    fun `idOfDirective -- returns directive when present`() {
        val testUser = schema.getObjectType("TestUser")
        val directive = testUser.getField("id3").idOfDirective
        assertNotNull(directive)
        assertEquals("idOf", directive!!.name)
        assertEquals("TestUser", directive.arguments.first { it.name == "type" }.getValue<String>())
    }

    @Test
    fun `idOfDirective -- returns null when absent`() {
        val testUser = schema.getObjectType("TestUser")
        assertNull(testUser.getField("id").idOfDirective)
    }

    @Test
    fun `globalIDType -- returns @idOf type arg when present`() {
        val objectWithGlobalIds = schema.getObjectType("ObjectWithGlobalIds")
        assertEquals(
            "TestUser",
            globalIDType(objectWithGlobalIds.getField("id1"), objectWithGlobalIds)
        )
    }

    @Test
    fun `globalIDType -- falls back to parent type name when no @idOf`() {
        val testUser = schema.getObjectType("TestUser")
        assertEquals(
            "TestUser",
            globalIDType(testUser.getField("id"), testUser)
        )
    }

    @Test
    fun `globalIDType -- ID typed input fields`() {
        val inp = schema.getTypeAs<GraphQLInputObjectType>("InputWithGlobalIDs")

        // field type is `ID!`, without @idOf
        assertThrows<AssertionError> {
            globalIDType(inp.getField("id"))
        }

        // field type is `ID!`, with @idOf
        assertEquals("O1", globalIDType(inp.getField("id2")))

        // field type is [[ID]!], with @idOf
        assertEquals("O2", globalIDType(inp.getField("ids")))
    }

    @Test
    fun `isGlobalID -- ID-typed input fields`() {
        val inp = schema.getTypeAs<GraphQLInputObjectType>("InputWithGlobalIDs")

        // field type is `ID!`, without @idOf
        assertFalse(isGlobalID(inp.getField("id")))

        // field type is `ID!`, with @idOf
        assertTrue(isGlobalID(inp.getField("id2")))

        // field type is [[ID]!], with @idOf
        assertTrue(isGlobalID(inp.getField("ids")))
    }
}
