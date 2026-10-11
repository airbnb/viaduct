@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import graphql.schema.GraphQLObjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime2.model.testing.fromSDL
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FieldError
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.runtime.jvm.OverlayEngineObjectData

class QPlanEngineObjectDataTest {
    private val fixture = ViaductAndGJSchema.fromSDL(SCHEMA_SDL)
    private val schema: ViaductSchema = fixture.loweredSchema
    private val userType = schema.requireType("User") as ViaductSchema.Object
    private val graphQLUserType = requireNotNull(fixture.graphQLSchema.getObjectType("User"))

    @Test
    fun `reads resolved objects directly without copying values or losing absence`() {
        val scores = listOf(1, null)
        val data = ResolvedEngineObjectData(
            graphQLUserType,
            mapOf("name" to "Ada", "nickname" to null, "age" to 36, "scores" to scores),
        )

        assertSame(userType, data.schemaType(schema))
        assertSame(userType, data.schemaType(userType))
        assertEquals("Ada", data.outputValue("name"))
        assertTrue(data.isPresent("nickname"))
        assertNull(data.outputValue("nickname"))
        assertEquals(36, data.outputValue("age"))
        assertSame(scores, data.outputValue("scores"))
        assertFalse(data.isPresent("missing"))
        assertFailsWith<UnsetFieldException> { data.outputValue("missing") }
    }

    @Test
    fun `reads arbitrary Sync objects with compliant stored output values`() {
        val scores = listOf(1, null)
        val other = OtherEngineObjectData(graphQLUserType, mapOf("name" to "Ada", "nickname" to null, "age" to 36, "scores" to scores))

        assertSame(userType, other.schemaType(schema))
        assertSame(userType, other.schemaType(userType))
        assertTrue(other.conformsToResolverOutputSchemaType(ViaductSchema.TypeExpr(userType)))
        assertEquals("Ada", other.outputValue("name"))
        assertTrue(other.isPresent("nickname"))
        assertNull(other.outputValue("nickname"))
        assertEquals(36, other.outputValue("age"))
        assertSame(scores, other.outputValue("scores"))
        assertFalse(other.isPresent("missing"))
        assertFailsWith<UnsetFieldException> { other.outputValue("missing") }
    }

    @Test
    fun `rejects incompatible Sync object types`() {
        val other = OtherEngineObjectData(graphQLUserType)
        assertFailsWith<IllegalArgumentException> { other.schemaType(schema.requireQueryTypeDef()) }
        assertFalse(other.conformsToResolverOutputSchemaType(ViaductSchema.TypeExpr(schema.requireQueryTypeDef())))

        val resolved = ResolvedEngineObjectData(graphQLUserType, emptyMap())
        assertFailsWith<IllegalArgumentException> { resolved.schemaType(schema.requireQueryTypeDef()) }
        val unknown = OtherEngineObjectData(GraphQLObjectType.newObject().name("Unknown").build())
        assertFailsWith<IllegalArgumentException> { unknown.schemaType(schema) }
        assertFalse(unknown.conformsToResolverOutputSchemaType(ViaductSchema.TypeExpr(userType)))
    }

    @Test
    fun `reads overlay output with null masking while retaining base values`() {
        val scores = listOf(1, null)
        val base = ResolvedEngineObjectData(graphQLUserType, mapOf("name" to "Ada", "nickname" to "Countess", "age" to 36, "scores" to scores))
        val overlay = OtherEngineObjectData(graphQLUserType, mapOf("name" to "Grace", "nickname" to null, "age" to 36))
        val data = OverlayEngineObjectData(overlay, base)

        assertSame(userType, data.schemaType(schema))
        assertSame(userType, data.schemaType(userType))
        assertTrue(data.conformsToResolverOutputSchemaType(ViaductSchema.TypeExpr(userType)))
        assertEquals(setOf("name", "nickname", "age", "scores"), data.getSelections().toSet())
        assertEquals("Grace", data.outputValue("name"))
        assertTrue(data.isPresent("nickname"))
        assertNull(data.outputValue("nickname"))
        assertEquals(36, data.outputValue("age"))
        assertSame(scores, data.outputValue("scores"))
        assertFalse(data.isPresent("missing"))
        assertFailsWith<UnsetFieldException> { data.outputValue("missing") }
    }

    @Test
    fun `does not translate getter exceptions into error markers`() {
        val error = ErroneousFieldException(listOf(FieldError("legacy field failure")))
        val data = object : EngineObjectData.Sync by OtherEngineObjectData(graphQLUserType, mapOf("age" to 36)) {
            override fun get(selection: String): Any? = throw error
        }

        assertTrue(data.isPresent("age"))
        assertSame(error, assertFailsWith<ErroneousFieldException> { data.outputValue("age") })
        assertFalse(error.conformsToResolverOutputSchemaType(schema.requireObjectField("User", "age").outputType))
        assertFailsWith<IllegalArgumentException> { engineObjectDataOf(userType, mapOf("age" to error)) }
    }

    @Test
    fun `distinguishes absent null value and error selections`() {
        val cause = IllegalStateException("source failure")
        val error = EngineErrorData.of(cause)
        val data =
            engineObjectDataOf(
                schemaType = userType,
                fields =
                    linkedMapOf(
                        "name" to "Ada",
                        "nickname" to null,
                        "age" to error,
                    ),
            )

        assertSame(graphQLUserType, data.type)
        assertEquals("Ada", data.get("name"))
        assertTrue(data.isPresent("nickname"))
        assertNull(data.get("nickname"))
        assertNull(data.getOrNull("nickname"))
        assertTrue(data.isPresent("age"))
        assertSame(error, data.outputValue("age"))
        val readError = assertFailsWith<EngineErrorDataReadException> { data.get("age") }
        assertSame(error, readError.errorData)
        assertSame(cause, readError.cause)
        assertSame(
            error,
            assertFailsWith<EngineErrorDataReadException> { data.getOrNull("age") }.errorData,
        )

        assertFalse(data.isPresent("missing"))
        assertNull(data.getOrNull("missing"))
        val exception = assertFailsWith<UnsetFieldException> { data.get("missing") }
        assertEquals("User", exception.typeName)
        assertFailsWith<UnsetFieldException> { data.outputValue("missing") }
    }

    @Test
    fun `suspending operations preserve synchronous read behavior`() =
        runBlocking {
            val error = EngineErrorData.of()
            val data =
                engineObjectDataOf(
                    schemaType = userType,
                    fields =
                        linkedMapOf(
                            "name" to "Ada",
                            "nickname" to null,
                            "age" to error,
                        ),
                )

            assertEquals("Ada", data.fetch("name"))
            assertNull(data.fetch("nickname"))
            assertSame(
                error,
                assertFailsWith<EngineErrorDataReadException> { data.fetch("age") }.errorData,
            )
            assertSame(
                error,
                assertFailsWith<EngineErrorDataReadException> {
                    data.fetchOrNull("age")
                }.errorData,
            )
            assertNull(data.fetchOrNull("missing"))
            assertEquals(
                listOf("name", "nickname", "age"),
                data.fetchSelections().toList(),
            )
            assertFailsWith<UnsetFieldException> { data.fetch("missing") }
        }

    @Test
    fun `resolver reads surface errors nested in lists while outputValue preserves the list`() {
        val error = EngineErrorData.of()
        val scores: EngineOutputListData = listOf(1, error, 3)
        val data = engineObjectDataOf(userType, mapOf("scores" to scores))

        assertSame(scores, data.outputValue("scores"))
        assertSame(
            error,
            assertFailsWith<EngineErrorDataReadException> { data.get("scores") }.errorData,
        )
    }

    @Test
    fun `supports response aliases without exposing schema field metadata`() {
        val data =
            engineObjectDataOf(
                schemaType = userType,
                fields =
                    listOf(
                        entry("displayName", "name", "Ada"),
                        entry("friendAtLimit", "friend", null),
                    ),
            )

        assertEquals(
            listOf("displayName", "friendAtLimit"),
            data.getSelections().toList(),
        )
        assertEquals("Ada", data.get("displayName"))
        assertTrue(data.isPresent("friendAtLimit"))
        assertFalse(data.isPresent("name"))
        assertFalse(data.isPresent("friend"))
    }

    @Test
    fun `snapshots passive field maps`() {
        val fields =
            linkedMapOf<String, EngineOutputData?>(
                "name" to "Ada",
            )
        val data = engineObjectDataOf(userType, fields)

        fields["name"] = "Grace"
        fields["nickname"] = "Countess"

        assertEquals("Ada", data.get("name"))
        assertFalse(data.isPresent("nickname"))
        assertEquals(listOf("name"), data.getSelections().toList())
    }

    @Test
    fun `rejects values that do not conform to their schema field`() {
        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = userType,
                fields = mapOf("name" to 1),
            )
        }
    }

    @Test
    fun `rejects entries owned by another object type`() {
        val queryField = schema.requireObjectField("Query", "viewer")

        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = userType,
                fields =
                    listOf(
                        EngineObjectDataEntry.of("viewer", queryField, null),
                    ),
            )
        }
    }

    @Test
    fun `rejects duplicate response selections`() {
        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = userType,
                fields =
                    listOf(
                        entry("value", "name", "Ada"),
                        entry("value", "age", 36),
                    ),
            )
        }
    }

    @Test
    fun `passive construction rejects argument-bearing fields`() {
        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = userType,
                fields = mapOf("friend" to null),
            )
        }
    }

    @Test
    fun `uses the exact source GraphQL Java definition while retaining lowered semantic identity`() {
        val data = engineObjectDataOf(userType)

        assertSame(userType, data.schemaType)
        assertSame(graphQLUserType, data.type)
        assertTrue(data.type.getFieldDefinition("name") != null)
        assertNull(data.type.getFieldDefinition("V_A_typename"))
    }

    @Test
    fun `qplan objects use reference equality`() {
        val first =
            engineObjectDataOf(
                userType,
                linkedMapOf("name" to "Ada", "nickname" to null),
            )
        val sameValue =
            engineObjectDataOf(
                userType,
                linkedMapOf("name" to "Ada", "nickname" to null),
            )

        assertSame(first, first)
        assertNotEquals(first, sameValue)
        assertNotEquals(first, OtherEngineObjectData(graphQLUserType))
    }

    private fun entry(
        selection: String,
        fieldName: String,
        value: EngineOutputData?,
    ): EngineObjectDataEntry =
        EngineObjectDataEntry.of(
            selection = selection,
            field = schema.requireObjectField("User", fieldName),
            value = value,
        )

    private class OtherEngineObjectData(
        type: GraphQLObjectType,
        fields: Map<String, Any?> = emptyMap(),
    ) : EngineObjectData.Sync by ResolvedEngineObjectData(type, fields)

    private companion object {
        val SCHEMA_SDL =
            """
            type User {
              name: String!
              nickname: String
              age: Int
              scores: [Int]
              friend(limit: Int): User
            }

            type Query {
              viewer: User
            }
            """.trimIndent()
    }
}
