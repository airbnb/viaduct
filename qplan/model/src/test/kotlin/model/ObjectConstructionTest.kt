package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import model.testing.TestWorld
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class ObjectConstructionTest {
    @Test
    fun `constructs fields using names and schema-directed output values`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema

        val friend =
            schema.objectOf("User") {
                "id" setTo "friend"
                "name" setTo "Grace"
            }
        val user =
            world.objectOf("User") {
                "id" setTo "user"
                "name" setTo "Ada"
                "age" setTo 36
                "score" setTo 9.5
                "active" setTo true
                "status" setTo "ACTIVE"
                "aliases" setTo listOf("A", "Countess")
                "friend" setTo friend
            }

        assertEquals(
            "user",
            user.get(key(schema, "id")),
        )
        assertEquals(
            "Ada",
            user.get(key(schema, "name")),
        )
        assertEquals(
            36,
            user.get(key(schema, "age")),
        )
        assertEquals(
            9.5,
            user.get(key(schema, "score")),
        )
        assertEquals(
            true,
            user.get(key(schema, "active")),
        )
        assertEquals(
            "ACTIVE",
            user.get(key(schema, "status")),
        )
        val aliases =
            assertIs<List<*>>(user.get(key(schema, "aliases")))
        assertEquals(
            listOf("A", "Countess"),
            aliases,
        )
        assertEquals(friend, user.get(key(schema, "friend")))
    }

    @Test
    fun `object construction rejects argument-bearing passive fields`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val friend = world.schema.objectOf("User")

        assertFailsWith<IllegalArgumentException> {
            world.objectOf("User") {
                field("friend", "limit" to 1) setTo friend
            }
        }
    }

    @Test
    fun `object values retain only explicitly supplied fields`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userType = schema.requireType("User") as ViaductSchema.Object
        val typenameKey = "V_A_typename"
        val typenameValue = "User"

        val implicit = engineObjectDataOf(userType)
        val explicit = engineObjectDataOf(userType, mapOf(typenameKey to typenameValue))

        assertEquals(emptyList(), implicit.getSelections().toList())
        assertEquals(
            typenameValue,
            explicit.get(typenameKey),
        )
    }

    @Test
    fun `nested object construction retains the shared schema context`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        val user =
            schema.objectOf("User") {
                "friend" setTo
                    objectOf("User") {
                        "name" setTo "Grace"
                    }
            }

        val friend =
            assertIs<EngineObjectData.Sync>(user.get(key(schema, "friend")))
        assertEquals(
            "Grace",
            friend.get(key(schema, "name")),
        )
    }

    @Test
    fun `rejects ambiguous or invalid object assignments`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema

        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                "name" setTo "Ada"
                "name" setTo "Grace"
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                field("friend", "limit" to 1, "limit" to 2) setTo null
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("User") {
                "name" setTo 1
            }
        }
        assertFailsWith<IllegalArgumentException> {
            schema.objectOf("Status")
        }
    }

    @Test
    fun `object fields reject keys owned by another concrete object type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val userType = schema.requireType("User") as ViaductSchema.Object
        val user = schema.objectOf("User")
        val viewerField = schema.requireObjectField("Query", "viewer")

        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = userType,
                fields =
                    listOf(
                        EngineObjectDataEntry.of("viewer", viewerField, user),
                    ),
            )
        }
    }

    @Test
    fun `completed object scopes cannot mutate constructed values`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        lateinit var scope: ObjectValueScope
        val user =
            schema.objectOf("User") {
                scope = this
                "name" setTo "Ada"
            }

        assertFailsWith<IllegalArgumentException> {
            with(scope) {
                "age" setTo 36
            }
        }
        assertEquals(
            setOf(key(schema, "name")),
            user.getSelections().toSet(),
        )
    }

    private fun key(
        schema: ViaductSchema,
        fieldName: String,
    ): String {
        schema.requireObjectField("User", fieldName)
        return fieldName
    }

    private companion object {
        val SCHEMA_SDL =
            """
            enum Status {
              ACTIVE
              INACTIVE
            }

            type User {
              id: ID!
              name: String!
              age: Int
              score: Float
              active: Boolean!
              status: Status!
              aliases: [String!]!
              friend(limit: Int): User
            }

            type Query {
              viewer: User
            }
            """.trimIndent()
    }
}
