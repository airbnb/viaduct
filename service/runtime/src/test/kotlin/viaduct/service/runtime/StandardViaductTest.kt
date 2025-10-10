@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager

class StandardViaductTest {
    private lateinit var subject: StandardViaduct
    private lateinit var dataFetcherExceptionHandler: DataFetcherExceptionHandler
    private lateinit var flagManager: FlagManager
    private val SCHEMA_ID = ""

    @BeforeEach
    fun setUp() {
        flagManager = mockk()
        dataFetcherExceptionHandler = mockk()
    }

    private fun createSimpleStandardViaduct() {
        createStandardViaduct()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createStandardViaduct() {
        // Create a basic schema for testing
        val sdl =
            """
                extend type Query {
                    test: String
                }
            """

        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withDataFetcherExceptionHandler(dataFetcherExceptionHandler)
            .withSchemaConfiguration(schemaConfiguration)
            .build()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    internal class SuccessfulExecutionResult : ExecutionResult {
        override fun getErrors(): MutableList<GraphQLError> = mutableListOf()

        override fun <T : Any?> getData() = null

        override fun isDataPresent() = true

        override fun getExtensions(): MutableMap<Any, Any> = mutableMapOf()

        override fun toSpecification(): MutableMap<String, Any> = mutableMapOf()
    }

    @Test
    fun `sortExecutionResult sorts result with empty details`() {
        createStandardViaduct()

        val executionResult = mockk<ExecutionResult>()

        val graphqlErrors = listOf(GraphQLError.newError().message("Error").build())

        every {
            executionResult.getData<String>()
        } returns "Test"

        every {
            executionResult.errors
        } returns graphqlErrors

        every {
            executionResult.extensions
        } returns mapOf()

        val executionResultImpl = subject.sortExecutionResult(executionResult)

        assertEquals("Test", executionResultImpl.getData())
        assertEquals(graphqlErrors, executionResultImpl.errors)
        assertEquals(mapOf(), executionResultImpl.extensions)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `test registerScopedSchema from schema registry builder builder`() {
        val fullSchema = makeSchema(
            """
                extend type Query @scope(to: ["scope1"]) {
                  field1: String
                }

                extend type Query @scope(to: ["scope2"]) {
                  field2: String
                }

                type Foo implements Node @scope(to: ["*"]) { # Ensure Query.node/s get created
                  id: ID!
                }
            """.trimIndent()
        )

        val schemaId = SchemaId.Scoped(SCHEMA_ID, setOf("scope1"))
        val config = SchemaConfiguration.fromSchema(
            fullSchema,
            scopes = setOf(schemaId.toScopeConfig())
        )
        val viaductBuilder = StandardViaduct.Builder().withSchemaConfiguration(config)

        val stdViaduct = viaductBuilder.build()
        val queryType = stdViaduct.getSchema(schemaId).schema.typeMap["Query"] as GraphQLObjectType
        val queryFields = queryType.fieldDefinitions?.map { it.name }

        assertEquals(listOf("field1", "node", "nodes"), queryFields)
    }

    @Test
    fun `executeAsync returns error for missing schema`() {
        val query = "{ test }"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput.create(operationText = query, requestContext = context)

        createSimpleStandardViaduct()

        runBlocking {
            val result = subject.executeAsync(executionInput, SchemaId.None).join()
            val errors = result.errors
            assertEquals(1, errors.size)
            assertEquals("Schema not found for schemaId=SchemaId(id='NONE')", errors.first().message)
            assertNull(result.getData())
        }
    }

    @Test
    fun `test newForSchema creates new instance with different schema registry`() {
        createSimpleStandardViaduct()
        // Create a new schema configuration
        val sdl =
            """
            extend type Query {
                newTest: String
            }
            """

        val newSchemaRegistryConfig = SchemaConfiguration.fromSdl(sdl)

        val newViaduct = subject.newForSchema(newSchemaRegistryConfig)

        // Verify that we got a new instance with different schema registry
        assertNotNull(newViaduct)
        assertNotNull(newViaduct.engineRegistry)
        // The schema registries should be different instances
        assertTrue(newViaduct.engineRegistry != subject.engineRegistry)
    }
}

private fun makeSchema(schema: String): ViaductSchema {
    return ViaductSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(schema).apply {
                DefaultSchemaProvider.addDefaults(this)
            }
        )
    )
}
