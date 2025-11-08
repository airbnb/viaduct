@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import graphql.schema.GraphQLInputObjectType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.mocks.MockInternalContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.TestType
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.gj

class WrapUtilsTest {
    private val schema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(schema, "viaduct.api.testschema")

    @Test
    fun `wrapEnum -- simple`() {
        assertEquals(
            E1.A,
            wrapEnum(internalContext, schema.schema.getTypeAs("E1"), "A")
        )
    }

    @Test
    fun `wrapEnum -- throws on unknown value`() {
        assertThrows<IllegalArgumentException> {
            wrapEnum(
                internalContext,
                schema.schema.getTypeAs("E1"),
                "Unknown"
            )
        }
    }

    @Test
    fun `wrapEnum -- returns null for UNDEFINED`() {
        assertNull(
            wrapEnum(
                internalContext,
                schema.schema.getTypeAs("E1"),
                "UNDEFINED"
            )
        )
    }

    @Test
    fun `wrapInputObject -- simple`() {
        val inp = wrapInputObject(
            internalContext,
            Input2.Reflection,
            schema.schema.getTypeAs("Input2"),
            mapOf("stringField" to "foo")
        )
        assertEquals("foo", inp.stringField)
    }

    @Test
    fun `wrapOutputObject -- simple`(): Unit =
        runBlocking {
            val obj = wrapOutputObject(
                internalContext,
                TestType.Reflection,
                ResolvedEngineObjectData(
                    schema.schema.getTypeAs("TestType"),
                    mapOf("id" to "foo")
                )
            )
            assertEquals("foo", obj.getId())
        }

    @Test
    fun `isGlobalID -- true for id field of concrete Node type`() {
        assertTrue(
            isGlobalID(
                field = schema.schema.getFieldDefinition(("TestUser" to "id").gj),
                parentType = schema.schema.getObjectType("TestUser")
            )
        )
    }

    @Test
    fun `isGlobalID -- ID-typed output fields`() {
        val testUser = schema.schema.getObjectType("TestUser")

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
    fun `isGlobalID -- ID-typed input fields`() {
        val inp = schema.schema.getTypeAs<GraphQLInputObjectType>("InputWithGlobalIDs")

        // field type is `ID!`, without @idOf
        assertFalse(isGlobalID(inp.getField("id")))

        // field type is `ID!`, with @idOf
        assertTrue(isGlobalID(inp.getField("id2")))

        // field type is [[ID]!], with @idOf
        assertTrue(isGlobalID(inp.getField("ids")))
    }
}
