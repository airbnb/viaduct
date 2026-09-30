@file:Suppress("ForbiddenImport")

package model

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
import model.testing.ViaductAndGJSchema
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema

class QPlanEngineObjectDataTest {
    private val fixture = ViaductAndGJSchema.fromSDL(SCHEMA_SDL)
    private val schema: ViaductSchema = fixture.loweredSchema
    private val userType = schema.requireType("User") as ViaductSchema.Object
    private val graphQLUserType = requireNotNull(fixture.graphQLSchema.getObjectType("User"))

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
        override val type: GraphQLObjectType,
    ) : EngineObjectData.Sync {
        override suspend fun fetch(selection: String): Any? = null

        override suspend fun fetchOrNull(selection: String): Any? = null

        override suspend fun fetchSelections(): Iterable<String> = emptyList()

        override fun get(selection: String): Any? = null

        override fun getOrNull(selection: String): Any? = null

        override fun isPresent(selection: String): Boolean = false

        override fun getSelections(): Iterable<String> = emptyList()
    }

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
