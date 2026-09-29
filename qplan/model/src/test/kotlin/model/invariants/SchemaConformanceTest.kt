package model.invariants

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.engineResultOf
import model.nodeRootFieldReferenceOf
import model.objectOf
import model.outputType
import model.requireField
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class SchemaConformanceTest {
    @Test
    fun `factory-constructed values conform to schema`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val schema = world.schema
        val value =
            world.objectOf("User") {
                "name" setTo "Ada"
            }

        assertTrue(
            value.conformsToSchema() &&
                value.conformsToOutputSchema(schema.requireField("Query", "user").outputType),
        )
    }

    @Test
    fun `factory-constructed engine results conform to schema`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result =
            world.engineResultOf("User") {
                "name" resolvesTo "Ada"
            }

        assertTrue(result.conformsToSchema(world.parentFieldRelations))
    }

    @Test
    fun `root field references belong only to resolver output`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val field = world.schema.requireField("Query", "user") as ViaductSchema.ObjectField
        val listField = world.schema.requireField("Query", "users") as ViaductSchema.ObjectField
        val reference = RootFieldReferenceData.of(listOf(field), emptyMap())

        assertFalse(reference.conformsToOutputSchemaType(field.outputType))
        assertTrue(reference.conformsToResolverOutputSchemaType(field.outputType))
        assertFalse(listOf(reference).conformsToOutputSchemaType(listField.outputType))
        assertTrue(listOf(reference).conformsToResolverOutputSchemaType(listField.outputType))
    }

    @Test
    fun `abstract root field references conform only when every possible type fits`() {
        val world = TestWorld.fromSDL(POLYMORPHIC_SCHEMA_SDL).assumptions
        val schema = world.schema
        val target = schema.requireField("Query", "search") as ViaductSchema.ObjectField
        val compatibleConsumer = schema.requireField("Container", "entity")
        val partialConsumer = schema.requireField("Container", "product")
        val reference = RootFieldReferenceData.of(listOf(target), emptyMap())

        assertTrue(reference.conformsToResolverOutputSchemaType(target.outputType))
        assertTrue(reference.conformsToResolverOutputSchemaType(compatibleConsumer.outputType))
        assertFalse(reference.conformsToResolverOutputSchemaType(partialConsumer.outputType))
    }

    @Test
    fun `Query node references conform by their encoded concrete type including in lists`() {
        val schema = TestWorld.fromSDL(NODE_SCHEMA_SDL).schema
        val queryNode = schema.requireObjectField("Query", "node")
        val user = schema.requireType("User") as ViaductSchema.Object
        val admin = schema.requireType("Admin") as ViaductSchema.Object
        val userReference = nodeRootFieldReferenceOf(queryNode, user, "user-1")
        val adminReference = nodeRootFieldReferenceOf(queryNode, admin, "admin-1")
        val userField = schema.requireObjectField("Container", "user")
        val usersField = schema.requireObjectField("Container", "users")

        assertTrue(userReference.conformsToResolverOutputSchemaType(userField.outputType))
        assertFalse(adminReference.conformsToResolverOutputSchemaType(userField.outputType))
        assertTrue(listOf(userReference).conformsToResolverOutputSchemaType(usersField.outputType))
        assertFalse(listOf(adminReference).conformsToResolverOutputSchemaType(usersField.outputType))
    }

    @Test
    fun `object value factory rejects a field value with the wrong type`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val user = schema.requireType("User") as ViaductSchema.Object

        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(
                schemaType = user,
                fields =
                    mapOf(
                        "name" to 1,
                    ),
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
              users: [User]
            }
            """.trimIndent()

        val POLYMORPHIC_SCHEMA_SDL =
            """
            interface Entity {
              name: String!
            }

            interface ProductEntity {
              name: String!
            }

            type Product implements Entity & ProductEntity {
              name: String!
            }

            type Service implements Entity {
              name: String!
            }

            union SearchResult = Product | Service

            type Container {
              entity: Entity
              product: ProductEntity
            }

            type Query {
              search: SearchResult
            }
            """.trimIndent()

        val NODE_SCHEMA_SDL =
            """
            interface Node { id: ID! }
            type User implements Node { id: ID! }
            type Admin implements Node { id: ID! }
            type Container {
              user: User
              users: [User]
            }
            type Query { container: Container }
            """.trimIndent()
    }
}
