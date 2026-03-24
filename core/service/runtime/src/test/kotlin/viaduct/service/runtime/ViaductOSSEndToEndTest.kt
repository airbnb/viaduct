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
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag

/**
 * End-to-end tests for the Viaduct OSS interface.
 *
 * As we expand the OSS interface to include more of the Viaduct Modern surface area, these test will expand to cover
 * the end-to-end constract of the Viaduct OSS framework.
 */
@ExperimentalCoroutinesApi
class ViaductOSSEndToEndTest {
    private lateinit var subject: StandardViaduct
    private lateinit var schemaConfiguration: SchemaConfiguration
    private lateinit var schemaId: SchemaId.Scoped

    private val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val sdl =
        """
        extend type Query @scope(to: ["viaduct-public"]) { field: Int }
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        schemaId = SchemaId.Scoped("public", setOf("viaduct-public"))
        schemaConfiguration = SchemaConfiguration.fromSdl(
            sdl,
            scopes = setOf(schemaId.toScopeConfig())
        )
        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaConfiguration(schemaConfiguration)
            .build()
    }

    @Test
    fun `getAppliedScopes on public returns viaduct-public`() {
        val scopes = subject.getAppliedScopes(schemaId)
        assertEquals(setOf("viaduct-public"), scopes)
    }

    @Test
    fun `getAppliedScopes on invalid schema throws`() {
        assertThrows<EngineRegistry.SchemaNotFoundException> {
            subject.getAppliedScopes(SchemaId.None)
        }
    }

    @Test
    fun `Viaduct with no instrumentations or wirings successfully returns null for valid query`() =
        runBlocking {
            val query = """
            query TestQuery {
                field
            }
            """.trimIndent()
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.execute(executionInput, schemaId)
            val actualAsynced = subject.executeAsync(executionInput, schemaId).await()
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
            val executionInput = ExecutionInput.create(operationText = query, requestContext = object {})

            val actual = subject.executeAsync(executionInput, schemaId).await()
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
            val executionInput = ExecutionInput.create(operationText = query)

            val result = subject.executeAsync(executionInput, SchemaId.None).await()
            assertEquals(1, result.errors.size)
            assertEquals("Schema not found for schemaId=SchemaId(id='NONE')", result.errors.first().message)
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

            val schemaId = SchemaId.Scoped("exception-test", setOf("viaduct-public"))
            val exceptionSchemaConfig = SchemaConfiguration.fromSchema(
                exceptionSchema,
                scopes = setOf(schemaId.toScopeConfig())
            )

            val exceptionSubject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(exceptionSchemaConfig)
                .build()

            val query = "query { field }"
            val executionInput = ExecutionInput.create(operationText = query)

            val result = exceptionSubject.executeAsync(executionInput, schemaId).await()
            assertEquals(1, result.errors.size)
            assertEquals("java.lang.RuntimeException: Data fetcher error", result.errors.first().message)
            assertNull(result.getData()?.get("field"))
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

            val schemaId = SchemaId.Scoped("variable-test", setOf("viaduct-public"))
            val variableSchemaConfig = SchemaConfiguration.fromSchema(
                variableSchema,
                scopes = setOf(schemaId.toScopeConfig())
            )

            val variableSubject = StandardViaduct.Builder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(variableSchemaConfig)
                .build()

            val query = """
                query TestQuery(${'$'}name: String!) {
                    fieldWithInput(input: ${'$'}name)
                }
            """.trimIndent()
            val variables = mapOf("name" to "World")
            val executionInput = ExecutionInput.create(operationText = query, variables = variables)

            val result = variableSubject.executeAsync(executionInput, schemaId).await()
            assertEquals(mapOf("fieldWithInput" to "Hello, World!"), result.getData())
            assertEquals(0, result.errors.size)
        }

    private fun mkSchema(
        sdl: String,
        wiring: RuntimeWiring
    ): ViaductSchema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), wiring))
}
