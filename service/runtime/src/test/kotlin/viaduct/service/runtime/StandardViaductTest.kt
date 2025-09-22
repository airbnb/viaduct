@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.getLocalContextForType
import viaduct.engine.runtime.instrumentation.ResolverInstrumentation
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.ViaductSchemaRegistryBuilder.AsyncScopedSchema

class StandardViaductTest {
    private lateinit var subject: StandardViaduct
    private lateinit var mockGraphql: GraphQL
    private lateinit var fullSchema: GraphQLSchema
    private lateinit var graphQLSchemaRegistry: ViaductSchemaRegistry
    private lateinit var instrumentation: Instrumentation
    private lateinit var queryExecutionStrategy: ExecutionStrategy
    private lateinit var mutationExecutionStrategy: ExecutionStrategy
    private lateinit var subscriptionExecutionStrategy: ExecutionStrategy
    private lateinit var dataFetcherExceptionHandler: DataFetcherExceptionHandler
    private lateinit var flagManager: FlagManager
    private lateinit var builder: StandardViaduct.Builder
    private val SCHEMA_ID = ""

    @BeforeEach
    fun setUp() {
        mockGraphql = mockk<GraphQL>()

        fullSchema = mockk()
        every { fullSchema.allTypesAsList } returns listOf()

        graphQLSchemaRegistry = mockk()
        every { graphQLSchemaRegistry.getFullSchema() } returns ViaductSchema(fullSchema)
        every { graphQLSchemaRegistry.registerSchema(any(), any(), any(), any()) } returns Unit

        instrumentation = mockk()
        queryExecutionStrategy = mockk()
        mutationExecutionStrategy = mockk()
        subscriptionExecutionStrategy = mockk()
        flagManager = mockk()
        dataFetcherExceptionHandler = mockk()
        builder = mockk()
    }

    private fun createSimpleStandardViaduct() {
        val scopedSchemas = ConcurrentHashMap<String, GraphQLSchema>().apply {
            put(SCHEMA_ID, fullSchema)
        }
        createStandardViaduct(scopedSchemas = scopedSchemas)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createStandardViaduct(
        scopedSchemas: ConcurrentHashMap<String, GraphQLSchema> = ConcurrentHashMap(),
        asyncGeneratedSchemas: ConcurrentHashMap<String, AsyncScopedSchema> = ConcurrentHashMap(),
    ) {
        if (scopedSchemas.get(SCHEMA_ID) != null || asyncGeneratedSchemas.get(SCHEMA_ID) != null) {
            every { graphQLSchemaRegistry.getSchema(SCHEMA_ID) } returns ViaductSchema(fullSchema)
            every { graphQLSchemaRegistry.getEngine(SCHEMA_ID) } returns mockGraphql
        }

        subject = StandardViaduct(
            instrumentation,
            queryExecutionStrategy,
            mutationExecutionStrategy,
            subscriptionExecutionStrategy,
            graphQLSchemaRegistry,
            DispatcherRegistry.Empty,
            fragmentLoader = ViaductFragmentLoader(ViaductExecutableFragmentParser()),
            tenantNameResolver = TenantNameResolver(),
            resolverInstrumentation = mockk<ResolverInstrumentation>(),
            flagManager = FlagManager.default
        )
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
    fun `GraphQL engine executes request`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } returns CompletableFuture.supplyAsync { SuccessfulExecutionResult() }

        runBlocking {
            val result = subject.executeAsync(executionInput).join()
            assertTrue(result.isDataPresent)
        }
    }

    @Test
    fun `runQuery(_) executes request`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } returns CompletableFuture.supplyAsync { SuccessfulExecutionResult() }

        val result = subject.runQuery(query)
        assertTrue(result.isDataPresent)
    }

    @Test
    fun `runQuery(_,_) executes request`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } returns CompletableFuture.supplyAsync { SuccessfulExecutionResult() }

        val result = subject.runQuery("", query)
        assertTrue(result.isDataPresent)
    }

    @Test
    fun `GraphQL engine throws an error`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        val expectedError = RuntimeException("internal graphql error")
        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } returns CompletableFuture.supplyAsync { throw expectedError }

        runBlocking {
            subject.executeAsync(executionInput)
                .handle { result: ExecutionResult?, receivedErr: Throwable? ->
                    assertEquals(expectedError, receivedErr?.cause)
                    assertNull(result)
                }.join()
        }
    }

    @Test
    fun `GraphQL engine returns null result`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } returns CompletableFuture.supplyAsync { null }

        runBlocking {
            subject.executeAsync(executionInput)
                .handle { result: ExecutionResult?, receivedErr: Throwable? ->
                    assertEquals("Unknown GQ Error: ExecutionResult for schemaId=$SCHEMA_ID cannot be null", receivedErr?.cause?.message)
                    assertNull(result)
                }.join()
        }
    }

    @Test
    fun `GraphQL engine handles synchronous errors`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        val expectedException = RuntimeException("sync error")
        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } throws expectedException

        runBlocking {
            subject.executeAsync(executionInput)
                .handle { result: ExecutionResult?, receivedErr: Throwable? ->
                    assertEquals("sync error", receivedErr?.cause?.message)
                    assertNull(result)
                }.join()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `mkExecutionInput function uses all the parameters`() {
        val query = "test-query"
        val requestContext = object {}
        val variables = mapOf("test-key" to "test-value")
        val operationName = "execution"
        val executionInput = ExecutionInput(query, SCHEMA_ID, requestContext, variables, operationName)
        mockk<GraphQLSchema>()

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } answers {
            val input = firstArg<graphql.ExecutionInput>()
            assertEquals(query, input.query)
            assertEquals(variables, input.variables)
            assertEquals(operationName, input.operationName)
            assertEquals(requestContext, input.context)
            val engineExecutionContext = input.getLocalContextForType<EngineExecutionContextImpl>()
            assertNotNull(engineExecutionContext)
            assertEquals(graphQLSchemaRegistry.getFullSchema(), engineExecutionContext.fullSchema)
            CompletableFuture.supplyAsync { SuccessfulExecutionResult() }
        }

        runBlocking {
            val result = subject.executeAsync(executionInput).join()
            assertTrue(result.isDataPresent)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `mkExecutionInput function has empty default parameters`() {
        val query = "query"
        val context = object {}
        val executionInput = ExecutionInput(query, SCHEMA_ID, context)

        createSimpleStandardViaduct()

        every { mockGraphql.executeAsync(any<graphql.ExecutionInput>()) } answers {
            val input = firstArg<graphql.ExecutionInput>()
            assertEquals(query, input.query)
            assertEquals(mapOf(), input.variables)
            assertEquals(null, input.operationName)
            assertEquals(context, input.context)
            val engineExecutionContext = input.getLocalContextForType<EngineExecutionContextImpl>()
            assertNotNull(engineExecutionContext)
            assertEquals(graphQLSchemaRegistry.getFullSchema(), engineExecutionContext.fullSchema)
            CompletableFuture.supplyAsync { SuccessfulExecutionResult() }
        }

        runBlocking {
            val result = subject.executeAsync(executionInput).join()
            assertTrue(result.isDataPresent)
        }
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

        val registryBuilder = ViaductSchemaRegistryBuilder().withFullSchema(fullSchema).registerScopedSchema(
            SCHEMA_ID,
            setOf("scope1"),
        )
        val viaductBuilder = StandardViaduct.Builder().withSchemaRegistryBuilder(registryBuilder)

        val stdViaduct = viaductBuilder.build()
        val queryType = stdViaduct.getSchema(SCHEMA_ID)?.schema?.typeMap?.get("Query") as GraphQLObjectType?
        val queryFields = queryType?.fieldDefinitions?.map { it.name }

        assertEquals(listOf("field1", "node", "nodes"), queryFields)
    }

    @Test
    fun `executeAsync returns error for missing schema`() {
        val query = "query"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput(query, "missing_schema_id", context)
        every { graphQLSchemaRegistry.getEngine("missing_schema_id") } returns null

        createSimpleStandardViaduct()

        runBlocking {
            val result = subject.executeAsync(executionInput).join()
            val errors = result.errors
            assertEquals(1, errors.size)
            assertEquals("Schema not found for schemaId=missing_schema_id", errors.first().message)
            assertNull(result.getData())
        }
    }

    @Test
    fun `test newForSchema creates new instance with different schema registry`() {
        createSimpleStandardViaduct()

        val newViaductSchemaRegistryBuilder = mockk<ViaductSchemaRegistryBuilder>()
        val newSchemaRegistry = mockk<ViaductSchemaRegistry>()
        val newSchema = mockk<GraphQLSchema>()

        every { newViaductSchemaRegistryBuilder.build(any()) } returns newSchemaRegistry
        every { newSchemaRegistry.registerSchema(any(), any(), any(), any()) } returns Unit
        every { newSchema.allTypesAsList } returns listOf()
        every { newSchemaRegistry.getFullSchema() } returns ViaductSchema(newSchema)

        val newViaduct = subject.newForSchema(newViaductSchemaRegistryBuilder)

        assertEquals(newSchemaRegistry, newViaduct.viaductSchemaRegistry)
        assertEquals(subject.chainedInstrumentation, newViaduct.chainedInstrumentation)
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
