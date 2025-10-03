@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.InvalidSyntaxError
import graphql.language.SourceLocation
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager

/**
 * End-to-end tests for the Viaduct OSS interface.
 *
 * As we expand the OSS interface to include more of the Viaduct Modern surface area, these test will expand to cover
 * the end-to-end constract of the Viaduct OSS framework.
 */
@ExperimentalCoroutinesApi
class ViaductOSSEndToEndTest {
    private lateinit var subject: StandardViaduct
    private lateinit var schemaRegistryConfiguration: SchemaRegistryConfiguration

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val sdl =
        """
        extend type Query @scope(to: ["viaduct-public"]) { field: Int }
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        schemaRegistryConfiguration = SchemaRegistryConfiguration.fromSdl(
            sdl,
            scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("public", setOf("viaduct-public")))
        )
        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaRegistryConfiguration(schemaRegistryConfiguration)
            .build()
    }

    @Test
    fun `getAppliedScopes on public returns viaduct-public`() {
        val scopes = subject.getAppliedScopes("public")
        assertEquals(setOf("viaduct-public"), scopes)
    }

    @Test
    fun `getAppliedScopes on invalid returns null`() {
        val scopes = subject.getAppliedScopes("invalid")
        assertNull(scopes)
    }

    @Test
    fun `Viaduct with no instrumentations or wirings successfully returns null for valid query`() =
        runBlocking {
            val query = """
            query TestQuery {
                field
            }
            """.trimIndent()
            val executionInput = ExecutionInput.create(schemaId = "public", operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput)
            val actualAsynced = subject.executeAsync(executionInput).await()
            // Having an intermittent bug in synchronicity.  This is a workaround to ensure the execution
            val expected = ExecutionResult.newExecutionResult()
                .data(mapOf("field" to null))
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
            assertEquals(expected.toSpecification(), actualAsynced.toSpecification())
            // By the transitive property the following should never fail, but we're including it here out of paranoia
            assertEquals(actual.toSpecification(), actualAsynced.toSpecification())
        }

    @Test
    fun `Viaduct with no instrumentations or wirings returns failure for invalid query`() =
        runBlocking {
            val query = "query"
            val executionInput = ExecutionInput.create(schemaId = "public", operationText = query, requestContext = object {})

            val actual = subject.executeAsync(executionInput).await()
            val expected = ExecutionResult.newExecutionResult()
                .errors(
                    listOf(
                        InvalidSyntaxError(SourceLocation(1, 6), "Invalid syntax with offending token '<EOF>' at line 1 column 6")
                    )
                )
                .data(null)
                .build()

            assertEquals(expected.toSpecification(), actual.toSpecification())
        }

    @Test
    fun `executeAsync returns error for missing schema`() =
        runBlocking {
            val query = "query { field }"
            val executionInput = ExecutionInput.create(schemaId = "nonexistent_schema", operationText = query)

            val result = subject.executeAsync(executionInput).await()
            assertEquals(1, result.errors.size)
            assertEquals("Schema not found for schemaId=nonexistent_schema", result.errors.first().message)
            assertNull(result.getData())
        }

    @Test
    fun `handles exceptions from data fetchers gracefully`() =
        runBlocking {
            val exceptionWiring = RuntimeWiring.newRuntimeWiring()
                .type("Foo") { builder ->
                    builder.dataFetcher("field") { throw RuntimeException("Data fetcher error") }
                }
                .build()

            val exceptionSchema = mkSchema(
                """
                    directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

                    schema { query: Foo }
                    type Foo @scope(to: ["viaduct-public"]) { field: Int }
                """.trimIndent(),
                exceptionWiring
            )

            val exceptionSchemaConfig = SchemaRegistryConfiguration.fromSchema(
                exceptionSchema,
                scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("exception-test", setOf("viaduct-public")))
            )

            val exceptionSubject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(exceptionSchemaConfig)
                .build()

            val query = "query { field }"
            val executionInput = ExecutionInput.create(schemaId = "exception-test", operationText = query)

            val result = exceptionSubject.executeAsync(executionInput).await()
            assertEquals(1, result.errors.size)
            assertEquals("java.lang.RuntimeException: Data fetcher error", result.errors.first().message)
            assertNull(result.getData<Any>()?.let { (it as Map<*, *>)["field"] })
        }

    @Test
    fun `executes query with variables and operation name`() =
        runBlocking {
            val variableWiring = RuntimeWiring.newRuntimeWiring()
                .type("Foo") { builder ->
                    builder.dataFetcher("fieldWithInput") { env ->
                        val input = env.getArgument<String>("input")
                        "Hello, $input!"
                    }
                }
                .build()

            val variableSchema = mkSchema(
                """
                    directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

                    schema { query: Foo }
                    type Foo @scope(to: ["viaduct-public"]) {
                        fieldWithInput(input: String!): String
                    }
                """.trimIndent(),
                variableWiring
            )

            val variableSchemaConfig = SchemaRegistryConfiguration.fromSchema(
                variableSchema,
                scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("variable-test", setOf("viaduct-public")))
            )

            val variableSubject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaRegistryConfiguration(variableSchemaConfig)
                .build()

            val query = """
                query TestQuery(${'$'}name: String!) {
                    fieldWithInput(input: ${'$'}name)
                }
            """.trimIndent()
            val variables = mapOf("name" to "World")
            val executionInput = ExecutionInput.create(schemaId = "variable-test", operationText = query, variables = variables)

            val result = variableSubject.executeAsync(executionInput).await()
            assertEquals(mapOf("fieldWithInput" to "Hello, World!"), result.getData())
            assertEquals(0, result.errors.size)
        }

    private fun mkSchema(
        sdl: String,
        wiring: RuntimeWiring
    ): ViaductSchema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), wiring))
}
