package viaduct.engine

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import io.kotest.matchers.string.shouldContain
import java.math.BigInteger
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.bootstrap.ViaductResourceIndexes
import viaduct.engine.api.mocks.createCoroutineInterop

class SchemaFactoryTest {
    private val schemaFactory = SchemaFactory(createCoroutineInterop())

    @Test
    fun `indexed plugin scalars coerce literals and variables using JSON`(
        @TempDir tempDir: Path
    ) {
        val resource = "viaduct/centralSchema/schema/plugin/dev.example.federation/source/schema.graphqls"
        tempDir.resolve(resource).apply {
            parent.createDirectories()
            writeText("\n\nscalar FieldSet\nextend type Query { echo(value: FieldSet): String }")
        }
        tempDir.resolve(ViaductResourceIndexes.SCHEMA).apply {
            parent.createDirectories()
            writeText("$resource\n")
        }
        URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { loader ->
            val schema = schemaFactory.fromResources(null, null, loader).schema
            val type = schema.getType("FieldSet") as graphql.schema.GraphQLScalarType
            assertEquals("schema/plugin/dev.example.federation/source/schema.graphqls", type.definition.sourceLocation.sourceName)
            assertEquals(3, type.definition.sourceLocation.line)
            val seen = mutableListOf<Any?>()
            val registry = GraphQLCodeRegistry.newCodeRegistry(schema.codeRegistry).dataFetcher(
                FieldCoordinates.coordinates("Query", "echo"),
                DataFetcher { env ->
                    seen += env.getArgument<Any?>("value")
                    "ok"
                },
            ).build()
            val graphQL = GraphQL.newGraphQL(schema.transform { it.codeRegistry(registry) }).build()
            val literal = graphQL.execute("""{ echo(value: {items: [1, null, ENUM, true]}) }""")
            assertTrue(literal.errors.isEmpty(), literal.errors.toString())
            val variables = graphQL.execute(
                ExecutionInput.newExecutionInput().query("query(${ '$' }v: FieldSet) { echo(value: ${ '$' }v) }").variables(mapOf("v" to mapOf("items" to listOf(1, null, "ENUM", true))))
            )
            assertTrue(variables.errors.isEmpty(), variables.errors.toString())
            assertEquals(mapOf("items" to listOf(BigInteger.ONE, null, "ENUM", true)), seen[0])
            assertEquals(mapOf("items" to listOf(1, null, "ENUM", true)), seen[1])
        }
    }

    @Test
    fun `unattributed custom scalars remain unsupported by fromSdl`() {
        assertThrows<SchemaProblem> { schemaFactory.fromSdl("scalar Custom\nextend type Query { field(value: Custom): String }") }
    }

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
        fun `fromResources loads exact schema resources from the generated index`(
            @TempDir tempDir: Path
        ) {
            val schemaPath = "viaduct/centralSchema/partition/example/graphql/schema.graphqls"
            tempDir.resolve(ViaductResourceIndexes.SCHEMA).apply {
                parent.createDirectories()
                writeText("$schemaPath\n")
            }
            tempDir.resolve(schemaPath).apply {
                parent.createDirectories()
                writeText("extend type Query { indexed: String }")
            }

            URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
                val schema = schemaFactory.fromResources(
                    grtPackagePrefix = "viaduct.centralSchema",
                    filesIncluded = Regex(".*\\.graphqls"),
                    classLoader = classLoader,
                )

                assertNotNull(schema.schema.queryType.getFieldDefinition("indexed"))
            }
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
        fun `indexed parse errors retain resource URLs causes and plugin source locations`(
            @TempDir tempDir: Path
        ) {
            val first = "viaduct/centralSchema/schema/plugin/dev.example.plugin/source/types.graphqls"
            val second = "viaduct/centralSchema/schema/module/catalog/generated/dev.example.generator/types.graphqls"
            val variants = listOf(
                "\n\ntype Broken { value: }" to "type Extra { value: String }",
                "\n\ntype Shared { value: String }" to "type Shared { value: Int }",
            )
            variants.forEach { (left, right) ->
                val index = listOf(first, second).sorted().joinToString("\n", postfix = "\n")
                mapOf(first to left, second to right, ViaductResourceIndexes.SCHEMA to index).forEach { (path, content) ->
                    tempDir.resolve(path).apply { parent.createDirectories() }.writeText(content)
                }

                val error = assertThrows<ViaductSchemaLoadException> {
                    URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { loader -> schemaFactory.fromResources(null, null, loader) }
                }

                assertTrue(error.cause is IllegalArgumentException)
                error.message!! shouldContain tempDir.resolve(first).toUri().toURL().toExternalForm()
                error.message!! shouldContain tempDir.resolve(second).toUri().toURL().toExternalForm()
                error.message!! shouldContain "Internal framework/plugin error"
                error.message!! shouldContain "schema/plugin/dev.example.plugin/source/types.graphqls:3:"
            }
        }

        @Test
        fun `indexed schema validation errors pass through as SchemaProblem`(
            @TempDir tempDir: Path
        ) {
            val resource = "viaduct/centralSchema/schema/plugin/dev.example.plugin/source/types.graphqls"
            mapOf(resource to "extend type Query { broken: UnknownType }", ViaductResourceIndexes.SCHEMA to "$resource\n").forEach { (path, content) ->
                tempDir.resolve(path).apply { parent.createDirectories() }.writeText(content)
            }

            val error = assertThrows<SchemaProblem> {
                URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { loader -> schemaFactory.fromResources(null, null, loader) }
            }

            error.message!! shouldContain "UnknownType"
        }

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
        fun `test no schema files found throws ViaductSchemaLoadException`(
            @TempDir tempDir: Path
        ) {
            tempDir.resolve(ViaductResourceIndexes.SCHEMA).apply {
                parent.createDirectories()
                writeText("")
            }
            val exception = assertThrows<ViaductSchemaLoadException> {
                URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
                    schemaFactory.fromResources(
                        "nonexistent.package.that.does.not.exist",
                        Regex("nonexistent-file-pattern-xyz"),
                        classLoader,
                    )
                }
            }

            (exception.message ?: "") shouldContain "No GraphQL schema files found matching pattern"
            (exception.message ?: "") shouldContain "Please ensure your .graphqls files are available in the classpath"
        }

        @Test
        fun `fromResources requires a generated index`(
            @TempDir tempDir: Path
        ) {
            val exception = assertThrows<IllegalArgumentException> {
                URLClassLoader(arrayOf(tempDir.toUri().toURL()), null).use { classLoader ->
                    schemaFactory.fromResources(null, null, classLoader)
                }
            }

            (exception.message ?: "") shouldContain "Apply the Viaduct application Gradle plugin"
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
