@file:Suppress("DEPRECATION", "ForbiddenImport")

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory

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

        override fun <T : Any> getData(): T? = null

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
            executionResult.getData<Map<String, Any?>>()
        } returns mapOf("field" to "Test")

        every {
            executionResult.errors
        } returns graphqlErrors

        every {
            executionResult.extensions
        } returns mapOf()

        val executionResultImpl = subject.sortExecutionResult(executionResult)

        assertEquals(mapOf("field" to "Test"), executionResultImpl.getData())
        assertEquals(graphqlErrors, executionResultImpl.errors)
        assertEquals(emptyMap<String, Any>(), executionResultImpl.extensions)
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
    fun `build should throw GraphQLBuildError when schema contains Subscription extension in OSS mode`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                userUpdated: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl, scopes = emptySet())

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals("Viaduct does not currently support subscriptions.", exception.message)
    }

    @Test
    fun `subscription validation uses the base view when Base is not registered`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                internalUpdate: String @tenantLocal
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl, scopes = emptySet())

        val viaduct = assertDoesNotThrow {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(emptySet<SchemaId>(), viaduct.engineRegistry.getRegisteredSchemaIds())
    }

    @Test
    fun `build should allow Subscription when airbnbModeEnabled is true`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                userUpdated: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .enableAirbnbBypassDoNotUse(tenantNameResolver = TenantNameResolver())
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should succeed when schema has no Subscriptions in OSS mode`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Mutation {
                updateUser: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should throw GraphQLBuildError when resolver field has no registered executor`() {
        val sdl = """
            extend type Query {
                missing: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(true, exception.message?.contains("Query.missing"))
        assertEquals("MissingResolversException", exception.cause?.javaClass?.simpleName)
    }

    @Test
    fun `build should throw GraphQLBuildError when a namespace-type field is wrapped`() {
        // Built-in @namespaceType config generation runs during registry construction. A wrapped
        // (list/non-null) namespace field makes that generation throw; the failure must surface as a
        // GraphQLBuildError rather than leaking a raw Guice ProvisionException to the caller.
        val sdl = """
            type Listings @namespaceType {
                name: String
            }
            extend type Query {
                listings: [Listings]
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should validate tenant-local resolver fields against full schema`() {
        val sdl = """
            extend type Query {
                visible: String
                missingTenantLocal: String @tenantLocal @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(true, exception.message?.contains("Query.missingTenantLocal"))
        assertEquals("MissingResolversException", exception.cause?.javaClass?.simpleName)
    }

    @Test
    fun `build should succeed with lenient resolver validation when resolver field has no executor`() {
        val sdl = """
            extend type Query {
                missing: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .withLenientResolverValidation()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    /** Ensures [TenantModuleInjectorFactory.finalize] is called when bootstrapping via the file-based path. */
    @Test
    fun `withTenantModuleInjectorFactory routes through file-based path and calls finalize`() {
        val recording = RecordingFinalizingTenantModuleInjectorFactory()

        val sdl = """
            extend type Query {
                test: String
            }
        """.trimIndent()

        StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(recording)
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()

        assertEquals(true, recording.finalized, "finalize() must be called via the file-based bootstrap path")
    }

    @Test
    fun `caller supplied module config sources contribute resolvers`() {
        val sdl = """
            extend type Query {
                generatedRegistryTestField: String @resolver
            }
        """.trimIndent()
        val suppliedModule = EngineTestModule(sdl) {
            fieldWithValue("Query" to "generatedRegistryTestField", "caller-supplied")
        }

        val viaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()

        val result = runBlocking {
            viaduct.execute(
                ExecutionInput.create(
                    operationText = "{ generatedRegistryTestField }",
                    requestContext = Any(),
                ),
                SchemaId.Base,
            )
        }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("generatedRegistryTestField" to "caller-supplied"), result.getData())
    }

    @Test
    fun `buildWithReusedSchemas refreshes generated registry sources without rebuilding schemas`() {
        val sdl = """
            extend type Query {
                generatedRegistryTestField: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)
        val oldViaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(CodeInjector.Naive))
            .withExecutorRegistryConfigSources(
                listOf(generatedRegistryConfigSource(value = "old-registry"))
            )
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        val newViaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(CodeInjector.Naive))
            .withExecutorRegistryConfigSources(
                listOf(generatedRegistryConfigSource(value = "new-registry"))
            )
            .withSchemaConfiguration(schemaConfiguration)
            .buildWithReusedSchemas(oldViaduct)
        val result = runBlocking {
            newViaduct.execute(
                ExecutionInput.create(
                    operationText = "{ generatedRegistryTestField }",
                    requestContext = Any(),
                ),
                SchemaId.Base,
            )
        }

        assertSame(oldViaduct.getSchema(SchemaId.Base), newViaduct.getSchema(SchemaId.Base))
        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("generatedRegistryTestField" to "new-registry"), result.getData())
    }
}

private fun makeSchema(schema: String): ViaductSchema {
    return ViaductSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(schema).apply {
                DefaultSchemaFactory.addDefaults(this)
            }
        )
    )
}

/** Records whether [onBootstrapComplete] was invoked; used to verify the file-based bootstrap path fires. */
private class RecordingFinalizingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
    var finalized: Boolean = false

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?
    ): CodeInjector = CodeInjector.Naive

    override suspend fun onBootstrapComplete() {
        finalized = true
    }
}

private fun generatedRegistryConfigSource(value: String): ModuleConfigSource =
    ModuleConfigSource.from(
        InputStreamSource.fromString(
            """
            {
              "version": "1",
              "tenantName": "generatedregistrytest",
              "apiName": "kotlin",
              "executorFactory": "${GeneratedRegistryTestExecutorFactory::class.java.name}",
              "nodes": [],
              "fields": [
                {
                  "typeName": "Query",
                  "fieldName": "generatedRegistryTestField",
                  "isBatching": false,
                  "isSelective": false,
                  "attribution": "generated-registry-test",
                  "tenantAPIData": {
                    "value": "$value"
                  }
                }
              ]
            }
            """.trimIndent(),
            name = "generated-registry-$value",
        ),
    )

class GeneratedRegistryTestExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor =
        MockFieldUnbatchedResolverExecutor(
            resolverId = "${configData.typeName}.${configData.fieldName}",
        ) { _, _, _, _, _ -> configData.tenantAPIData["value"] as String }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("Node resolvers are not used by this test")
    }
}
