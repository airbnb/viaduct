package viaduct.service.runtime

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import kotlin.test.assertContains
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.mkCoroutineInterop
import viaduct.engine.api.mocks.mkExecutionStrategy
import viaduct.engine.api.mocks.mkInstrumentation

class ViaductSchemaRegistryAndBuilderTest {
    private val mockCoroutineInterop = mkCoroutineInterop()
    private val mockExecutionStrategy = mkExecutionStrategy()
    private val mockInstrumentation = mkInstrumentation()

    fun checkResults(
        registry: ViaductSchemaRegistry,
        schemaId: String
    ): ViaductSchema {
        val actualSchema = registry.getSchema(schemaId)
        assertNotNull(actualSchema)
        assertEquals("Query", actualSchema?.schema?.queryType?.name)

        val engine = registry.getEngine(schemaId)
        assertNotNull(engine?.graphQLSchema)
        assertEquals(actualSchema, ViaductSchema(requireNotNull(engine?.graphQLSchema)))

        val actualEngine = registry.getEngine(schemaId)
        assertNotNull(actualEngine?.graphQLSchema)
        assertEquals(actualSchema, ViaductSchema(requireNotNull(actualEngine?.graphQLSchema)))

        return actualSchema!!
    }

    @Test
    fun `test successful schema creation from SDL string`() {
        val validSchema = """
            extend type Query {
                hello: String
            }
        """.trimIndent()

        val builder = ViaductSchemaRegistryBuilder()
            .withFullSchemaFromSdl(validSchema)
            .registerFullSchema("testSchema")

        val registry = builder.build(mockCoroutineInterop)
        registry.registerSchema(mockInstrumentation, mockExecutionStrategy, mockExecutionStrategy, mockExecutionStrategy)

        checkResults(registry, "testSchema")
    }

    @Test
    fun `test successful scoped schema registration`() {
        val fullSchema = """
            extend type Query @scope(to: ["public"]) {
                hello: String
            }

            type PrivateQuery @scope(to: ["private"]) {
                privateField: String
            }
        """.trimIndent()

        val builder = ViaductSchemaRegistryBuilder()
            .withFullSchemaFromSdl(fullSchema)
            .registerScopedSchema("publicSchema", setOf("public"))

        val registry = builder.build(mockCoroutineInterop)
        registry.registerSchema(mockInstrumentation, mockExecutionStrategy, mockExecutionStrategy, mockExecutionStrategy)

        val actualSchema = checkResults(registry, "publicSchema")
        assertNotNull(actualSchema.schema.queryType!!.getField("hello"))
        assertNull(actualSchema.schema.queryType!!.getField("privateField"))
        assertNotEquals(registry.getFullSchema(), actualSchema)
    }

    @Test
    fun `test async schema registration with lazy loading`() {
        val fullSchema = """
            extend type Query {
                hello: String
            }
        """.trimIndent()

        var schemaComputeCalled = 0
        val schemaComputeBlock = {
            schemaComputeCalled++
            // needs to be a valid schema for the test to be valid
            makeTestSchema(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            )
        }

        val builder = ViaductSchemaRegistryBuilder()
            .withFullSchemaFromSdl(fullSchema)
            .registerSchema("asyncSchema", schemaComputeBlock, lazy = true)

        val registry = builder.build(mockCoroutineInterop)
        registry.registerSchema(mockInstrumentation, mockExecutionStrategy, mockExecutionStrategy, mockExecutionStrategy)

        // Schema compute block should not be called during build with lazy=true
        assertEquals(0, schemaComputeCalled, "Schema compute block should not be called during build when lazy=true")

        // Accessing the schema should trigger computation
        checkResults(registry, "asyncSchema")
        assertEquals(1, schemaComputeCalled, "Schema compute block should be called once when accessing lazy schema")
    }

    @Test
    fun `test async schema registration with eager loading`() {
        val fullSchema = """
            extend type Query {
                hello: String
            }
        """.trimIndent()

        var schemaComputeCalled = 0
        val schemaComputeBlock = {
            schemaComputeCalled++
            // needs to be a valid schema for the test to be valid
            makeTestSchema(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            )
        }

        val builder = ViaductSchemaRegistryBuilder()
            .withFullSchemaFromSdl(fullSchema)
            .registerSchema("asyncSchema", schemaComputeBlock, lazy = false)

        val registry = builder.build(mockCoroutineInterop)
        registry.registerSchema(mockInstrumentation, mockExecutionStrategy, mockExecutionStrategy, mockExecutionStrategy)

        // Schema compute block should be called during build with lazy=false
        assertEquals(1, schemaComputeCalled, "Schema compute block should be called when lazy=false")

        checkResults(registry, "asyncSchema")
        assertEquals(1, schemaComputeCalled, "Schema compute block should be called when lazy=false")
    }

    @Test
    fun `test withFullSchema with predefined schema`() {
        val predefinedSchema = makeTestSchema()

        val builder = ViaductSchemaRegistryBuilder()
            .withFullSchema(predefinedSchema)
            .registerFullSchema("testSchema")

        val registry = builder.build(mockCoroutineInterop)
        registry.registerSchema(mockInstrumentation, mockExecutionStrategy, mockExecutionStrategy, mockExecutionStrategy)

        assertEquals(predefinedSchema, registry.getSchema("testSchema"))
    }

    @Test
    fun `schema scopes extension finds all scopes`() {
        val allScopes = makeTestSchema(
            """
                directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

                type Query @scope(to: ["*"]) {
                  _: String @deprecated
                }

                extend type Query @scope(to: ["scope1"]) {
                  field1: String
                }

                extend type Query @scope(to: ["scope2", "scope3"]) {
                  field2: String
                }
            """.trimIndent()
        ).scopes()

        assertEquals(setOf("scope1", "scope2", "scope3"), allScopes)
    }

    // DefaultSchemaProvider Integration Tests

    @Test
    fun `default schema test - full integration test with FromSdl factory`() {
        val sdl = """
            type User implements Node {
              id: ID!
              name: String
            }

            extend type Query {
              users: [User]
            }
        """.trimIndent()

        val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
        val config = configBuilder.build(mockCoroutineInterop)
        val schema = config.getFullSchema()

        assertNotNull(schema.schema, "Schema should be created successfully")

        // Verify default directives are present
        assertNotNull(schema.schema.getDirective("resolver"), "Schema should contain @resolver directive")
        assertNotNull(schema.schema.getDirective("backingData"), "Schema should contain @backingData directive")
        assertNotNull(schema.schema.getDirective("scope"), "Schema should contain @scope directive")

        // Verify Node interface was added
        assertNotNull(schema.schema.getType("Node"), "Schema should contain Node interface")

        // Verify BackingData scalar was added
        assertNotNull(schema.schema.getType("BackingData"), "Schema should contain BackingData scalar")

        // Verify Query root type was added
        assertNotNull(schema.schema.queryType, "Schema should have Query root type")

        // Verify Mutation and Subscription were not added (no extensions)
        assertNull(schema.schema.mutationType, "Schema should not have Mutation type")
        assertNull(schema.schema.subscriptionType, "Schema should not have Subscription type")
    }

    @Test
    fun `default schema test - should error when builtin schema components are redefined`() {
        val sdl = """
            directive @resolver on FIELD_DEFINITION
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
            val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
            configBuilder.build(mockCoroutineInterop)
        }

        assertContains(
            exception.cause?.message ?: "",
            "cannot be redefined",
            message = "Should error when core directives are redefined"
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
            val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
            configBuilder.build(mockCoroutineInterop)
        }

        assertContains(
            exception.cause?.message ?: "",
            "Root type Query cannot be manually defined",
            message = "Should error when Query type is manually defined"
        )
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

        val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
        val config = configBuilder.build(mockCoroutineInterop)
        val schema = config.getFullSchema()

        assertNotNull(schema.schema, "Schema should be created successfully")
        assertNotNull(schema.schema.queryType, "Schema should always have Query type")
        assertNull(schema.schema.mutationType, "Schema should not have Mutation type (no extensions)")
        assertNull(schema.schema.subscriptionType, "Schema should not have Subscription type (no extensions)")

        // Verify default directives were added
        assertNotNull(schema.schema.getDirective("resolver"), "Schema should contain @resolver directive")
        assertNotNull(schema.schema.getDirective("backingData"), "Schema should contain @backingData directive")
        assertNotNull(schema.schema.getDirective("scope"), "Schema should contain @scope directive")

        // Verify Node interface was not added, because sdl contains no implementation of Node
        assertNull(schema.schema.getType("Node"), "Schema should not contain Node interface")

        // verify BackingData scalar was added
        assertNotNull(schema.schema.getType("BackingData"), "Schema should contain BackingData scalar")
    }

    @Test
    fun `default schema test - should error when manual root types conflict with extensions`() {
        val sdl = """
            type Query { # shouldn't be able to be defined manually
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
            val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
            configBuilder.build(mockCoroutineInterop)
        }

        assertContains(
            exception.cause?.message ?: "",
            "Root type Query cannot be manually defined",
            message = "Should error when Query definition conflicts with Query extensions"
        )
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

        val configBuilder = ViaductSchemaRegistryBuilder().withFullSchemaFromSdl(sdl)
        val config = configBuilder.build(mockCoroutineInterop)
        val schema = config.getFullSchema()

        assertNotNull(schema.schema, "Schema should be created successfully")
        assertNotNull(schema.schema.queryType, "Schema should have Query root type")
        assertNotNull(schema.schema.mutationType, "Schema should have Mutation root type")
        assertNotNull(schema.schema.subscriptionType, "Schema should have Subscription root type")
    }
}

internal fun makeTestSchema(sdl: String): ViaductSchema = ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)))

internal fun makeTestSchema(): ViaductSchema {
    return ViaductSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
                    type Query {
                        hello: String
                    }
                """.trimIndent()
            )
        )
    )
}
