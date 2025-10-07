package viaduct.service.runtime

import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop

class ViaductSchemaRegistryBuilderErrorTest {
    private lateinit var mockCoroutineInterop: CoroutineInterop
    private lateinit var mockInstrumentation: Instrumentation
    private lateinit var mockExecutionStrategy: ExecutionStrategy

    @BeforeEach
    fun setUp() {
        mockCoroutineInterop = mockk()
        mockInstrumentation = mockk()
        mockExecutionStrategy = mockk()
    }

    @Test
    fun `test empty SDL string throws ViaductSchemaLoadException with source info`() {
        val config = SchemaRegistryConfiguration.fromSdl("")

        val exception = assertThrows<ViaductSchemaLoadException> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }

        with(exception.message!!) {
            assertTrue(contains("GraphQL schema SDL is empty or contains only whitespace"))
            assertTrue(contains("Please provide a valid GraphQL schema definition"))
            // When SDL is provided directly (not from files), no source file info should be shown
            assertFalse(contains("Source files:"))
        }
    }

    @Test
    fun `test whitespace-only SDL string throws ViaductSchemaLoadException`() {
        val config = SchemaRegistryConfiguration.fromSdl("   \n\t   ")

        val exception = assertThrows<ViaductSchemaLoadException> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }

        assertTrue(exception.message!!.contains("GraphQL schema SDL is empty or contains only whitespace"))
    }

    @Test
    fun `test invalid GraphQL syntax throws ViaductSchemaLoadException with source info`() {
        val invalidSchema = "invalid graphql syntax {"

        val config = SchemaRegistryConfiguration.fromSdl(invalidSchema)

        val exception = assertThrows<ViaductSchemaLoadException> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }

        assertTrue(exception.message!!.contains("Failed to parse GraphQL schema"))
        assertTrue(exception.message!!.contains("Original error:"))
        // When SDL is provided directly (not from files), no source file info should be shown
        assertTrue(!exception.message!!.contains("Source files:"))
    }

    @Test
    fun `test schema validation errors pass through as SchemaProblem`() {
        // This schema has validation errors (missing Query type definition)
        val invalidSchema = """
            extend type Query {
                hello: UnknownType
            }
        """.trimIndent()

        val config = SchemaRegistryConfiguration.fromSdl(invalidSchema)

        // Should throw SchemaProblem, not IllegalStateException
        assertThrows<SchemaProblem> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }
    }

    @Test
    fun `test no schema files found throws ViaductSchemaLoadException`() {
        // This test verifies the error message when no schema files are found
        // We'll use a package prefix and file pattern that definitely doesn't exist
        val config = SchemaRegistryConfiguration
            .fromResources("nonexistent.package.that.does.not.exist", "nonexistent-file-pattern-xyz")

        val exception = assertThrows<ViaductSchemaLoadException> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }

        assertTrue(exception.message!!.contains("No GraphQL schema files found matching pattern"))
        assertTrue(exception.message!!.contains("Please ensure your .graphqls files are available in the classpath"))
    }

    @Test
    fun `test duplicate schema IDs throws IllegalStateException`() {
        val schema = """
            extend type Query {
                hello: String
            }
        """.trimIndent()

        val config = SchemaRegistryConfiguration.fromSdl(schema, fullSchemaIds = listOf("duplicateId")).apply {
            @Suppress("DEPRECATION")
            registerSchema("duplicateId", { makeTestSchema() })
        }

        val exception = assertThrows<IllegalStateException> {
            val factory = ViaductSchemaRegistry.Factory(mockCoroutineInterop)
            factory.createRegistry(config)
        }

        assertTrue(exception.message!!.contains("Duplicate schema IDs found"))
        assertTrue(exception.message!!.contains("duplicateId"))
    }

    private fun makeTestSchema(): ViaductSchema {
        val sdl = """
            extend type Query {
                hello: String
            }
        """.trimIndent()
        return ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), RuntimeWiring.MOCKED_WIRING))
    }
}
