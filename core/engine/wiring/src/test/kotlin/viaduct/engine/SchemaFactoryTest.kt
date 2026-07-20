package viaduct.engine

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.createCoroutineInterop

class SchemaFactoryTest {
    private val schemaFactory = SchemaFactory(createCoroutineInterop())

    @Nested
    inner class PositiveTests {
        @Test
        fun `test successful schema creation from SDL string`() {
            val validSchema = """
                extend type Query {
                    hello: String
                }
            """.trimIndent()

            val schema = schemaFactory.fromSdl(validSchema)

            assertNotNull(schema)
            assertEquals("Query", schema.schema.queryType?.name)
        }

        @Test
        fun `test prebuilt registry does not add duplicate default components`() {
            val registry = SchemaParser().parse(
                """
                directive @resolver on FIELD_DEFINITION

                type Query {
                    hello: String @resolver
                }
                """.trimIndent()
            )

            val schema = schemaFactory.fromPrebuiltTypeDefinitionRegistry(registry)

            val hello = schema.schema.queryType.getFieldDefinition("hello")
            assertNotNull(hello)
            assertNotNull(schema.schema.getDirective("resolver"))
            assertNotNull(hello.getAppliedDirective("resolver"))
            assertNull(hello.getDirective("resolver"))
        }

        @Test
        fun `default schema test - full integration test with fromSdl`() {
            val sdl = """
                type User implements Node {
                  id: ID!
                  name: String
                }

                extend type Query {
                  users: [User]
                }
            """.trimIndent()

            val schema = schemaFactory.fromSdl(sdl)

            assertNotNull(schema.schema, "Schema should be created successfully")

            assertNotNull(schema.schema.getDirective("resolver"), "Schema should contain @resolver directive")
            assertNotNull(schema.schema.getDirective("backingData"), "Schema should contain @backingData directive")
            assertNotNull(schema.schema.getDirective("scope"), "Schema should contain @scope directive")

            assertNotNull(schema.schema.getType("Node"), "Schema should contain Node interface")

            assertNotNull(schema.schema.getType("BackingData"), "Schema should contain BackingData scalar")

            assertNotNull(schema.schema.queryType, "Schema should have Query root type")

            assertNull(schema.schema.mutationType, "Schema should not have Mutation type")
            assertNull(schema.schema.subscriptionType, "Schema should not have Subscription type")
        }

        @Test
        fun `default schema test - should work with only user-defined types and no root types`() {
            val sdl = """
                type User {
                  name: String
                }

                type Product {
                  title: String
                }
            """.trimIndent()

            val schema = schemaFactory.fromSdl(sdl)

            assertNotNull(schema.schema, "Schema should be created successfully")
            assertNotNull(schema.schema.queryType, "Schema should always have Query type")
            assertNull(schema.schema.mutationType, "Schema should not have Mutation type (no extensions)")
            assertNull(schema.schema.subscriptionType, "Schema should not have Subscription type (no extensions)")

            assertNotNull(schema.schema.getDirective("resolver"), "Schema should contain @resolver directive")
            assertNotNull(schema.schema.getDirective("backingData"), "Schema should contain @backingData directive")
            assertNotNull(schema.schema.getDirective("scope"), "Schema should contain @scope directive")

            assertNull(schema.schema.getType("Node"), "Schema should not contain Node interface")

            assertNotNull(schema.schema.getType("BackingData"), "Schema should contain BackingData scalar")
        }

        @Test
        fun `default schema test - should create all root types when all have extensions`() {
            val sdl = """
                type User {
                  name: String
                }

                extend type Query {
                  users: [User]
                }

                extend type Mutation {
                  createUser(name: String!): User
                }

                extend type Subscription {
                  userUpdated: User
                }
            """.trimIndent()

            val schema = schemaFactory.fromSdl(sdl)

            assertNotNull(schema.schema, "Schema should be created successfully")
            assertNotNull(schema.schema.queryType, "Schema should have Query root type")
            assertNotNull(schema.schema.mutationType, "Schema should have Mutation root type")
            assertNotNull(schema.schema.subscriptionType, "Schema should have Subscription root type")
        }

        @Test
        fun `schema-level directives fall back to SchemaGenerator and are preserved`() {
            val sdl = """
                directive @schemaDirective on SCHEMA
                directive @schemaExtensionDirective on SCHEMA

                schema @schemaDirective {
                  query: Query
                }

                extend schema @schemaExtensionDirective

                type Query {
                  hello: String
                }
            """.trimIndent()

            val schema = schemaFactory.fromSdl(sdl)

            val directiveNames = schema.schema.schemaAppliedDirectives.map { it.name }.toSet()
            assertTrue("schemaDirective" in directiveNames, "Base schema directive should be preserved")
            assertTrue("schemaExtensionDirective" in directiveNames, "Schema extension directive should be preserved")
        }
    }

    @Nested
    inner class ErrorTests {
        @Test
        fun `test empty SDL string throws ViaductSchemaLoadException with source info`() {
            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl("")
            }

            with(exception.message ?: "") {
                this shouldContain "GraphQL schema SDL is empty or contains only whitespace"
                this shouldContain "Please provide a valid GraphQL schema definition"
            }
        }

        @Test
        fun `test whitespace-only SDL string throws ViaductSchemaLoadException`() {
            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl("   \n\t   ")
            }

            (exception.message ?: "") shouldContain "GraphQL schema SDL is empty or contains only whitespace"
        }

        @Test
        fun `test invalid GraphQL syntax throws ViaductSchemaLoadException with source info`() {
            val invalidSchema = "invalid graphql syntax {"

            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl(invalidSchema)
            }

            (exception.message ?: "") shouldContain "Failed to parse GraphQL schema"
            (exception.message ?: "") shouldContain "Original error:"
        }

        @Test
        fun `test schema validation errors pass through as SchemaProblem`() {
            val invalidSchema = """
                extend type Query {
                    hello: UnknownType
                }
            """.trimIndent()

            assertThrows<SchemaProblem> {
                schemaFactory.fromSdl(invalidSchema)
            }
        }

        @Test
        fun `test no schema files found throws ViaductSchemaLoadException`() {
            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromResources("nonexistent.package.that.does.not.exist", Regex("nonexistent-file-pattern-xyz"))
            }

            (exception.message ?: "") shouldContain "No GraphQL schema files found matching pattern"
            (exception.message ?: "") shouldContain "Please ensure your .graphqls files are available in the classpath"
        }

        @Test
        fun `default schema test - should error when builtin schema components are redefined`() {
            val sdl = """
                directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION
                directive @backingData(class: String!) on FIELD_DEFINITION
                directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

                type User {
                  name: String
                }

                extend type Query {
                  users: [User]
                }

                extend type Mutation {
                  createUser(name: String!): User
                }
            """.trimIndent()

            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl(sdl)
            }

            assertTrue(
                (exception.cause?.message ?: "").contains("cannot be redefined"),
                "Should error when core directives are redefined"
            )
        }

        @Test
        fun `default schema test - should error when manual Query type is defined`() {
            val sdl = """
                type Query {
                  existingQuery: String
                }

                type User {
                  name: String
                }
            """.trimIndent()

            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl(sdl)
            }

            assertTrue(
                (exception.cause?.message ?: "").contains("Root type Query cannot be manually defined"),
                "Should error when Query type is manually defined"
            )
        }

        @Test
        fun `default schema test - should error when manual root types conflict with extensions`() {
            val sdl = """
                type Query {
                  existingQuery: String
                }

                type User {
                  name: String
                }

                extend type Query {
                  users: [User]
                }

                extend type Mutation {
                  createUser(name: String!): User
                }
            """.trimIndent()

            val exception = assertThrows<ViaductSchemaLoadException> {
                schemaFactory.fromSdl(sdl)
            }

            assertTrue(
                (exception.cause?.message ?: "").contains("Root type Query cannot be manually defined"),
                "Should error when Query definition conflicts with Query extensions"
            )
        }
    }
}
