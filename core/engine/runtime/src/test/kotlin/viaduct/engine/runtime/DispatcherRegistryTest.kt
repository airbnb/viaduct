@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import graphql.language.AstPrinter
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.collections.count
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.KOTLIN_API_NAME
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.Samples
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.toDispatcherRegistryFactory
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.api.spi.TenantModuleException
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher
import viaduct.engine.runtime.tenantloading.ExecutorValidatorContext
import viaduct.engine.runtime.tenantloading.StandardDispatcherRegistryFactory
import viaduct.engine.runtime.validation.Validator
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.TenantModuleInjectorFactory

@ExperimentalCoroutinesApi
class DispatcherRegistryTest {
    private lateinit var modules: List<MockTenantModuleBootstrapper>
    private lateinit var checkerExecutorFactory: MockCheckerExecutorFactory

    @BeforeEach
    fun setUp() {
        modules = listOf(Samples.mockTenantModule)

        checkerExecutorFactory = MockCheckerExecutorFactory(
            mapOf(
                Pair("TestType", "aField") to MockCheckerExecutor(
                    requiredSelectionSets = mapOf(
                        "checker_0" to RequiredSelectionSet(
                            SelectionsParser.parse("TestType", "dField"),
                            emptyList(),
                            forChecker = true
                        ),
                        "checker_1" to null
                    )
                ),
                Pair("TestType", "bIntField") to MockCheckerExecutor()
            ),
            mapOf(
                "TestNode" to MockCheckerExecutor(
                    requiredSelectionSets = mapOf(
                        "key" to RequiredSelectionSet(
                            SelectionsParser.parse("TestNode", "id"),
                            emptyList(),
                            forChecker = true
                        )
                    )
                )
            )
        )
    }

    private fun createDispatcherRegistry() = dispatcherRegistryFactory(modules, Validator.Unvalidated, checkerExecutorFactory).create(Samples.testSchema) as DispatcherRegistry.Impl

    companion object {
        private fun dispatcherRegistryFactory(
            modules: List<MockTenantModuleBootstrapper>,
            validator: Validator<ExecutorValidatorContext>,
            checkerExecutorFactory: CheckerExecutorFactory,
            proxyResolverFactory: ProxyResolverFactory = ProxyResolverFactory.NO_OP,
        ) = modules.toDispatcherRegistryFactory(validator, checkerExecutorFactory, proxyResolverFactory)
    }

    @Test
    fun `test successful injection of dispatcher`(): Unit =
        runBlocking {
            val dispatcherRegistry = createDispatcherRegistry()
            // We have 6 resolvers: aField, bIntField, parameterizedField, cField, dField, batchField
            assertEquals(6, dispatcherRegistry.fieldResolverDispatchers.count())

            val objectType = Samples.testSchema.schema.getObjectType("TestType")
            assertEquals("TestType", objectType.name)

            val resolverDispatcher = dispatcherRegistry.getFieldResolverDispatcher("TestType", "aField")
            assertNotNull(resolverDispatcher)
            val batchResolverDispatcher = dispatcherRegistry.getFieldResolverDispatcher("TestType", "batchField")
            assertNotNull(batchResolverDispatcher)
            val checkerExecutor = dispatcherRegistry.getFieldCheckerDispatcher("TestType", "aField")
            assertNotNull(checkerExecutor)

            val resolverDispatcherInt = dispatcherRegistry.getFieldResolverDispatcher("TestType", "bIntField")
            assertNotNull(resolverDispatcherInt)
            val checkerExecutorB = dispatcherRegistry.getFieldCheckerDispatcher("TestType", "bIntField")
            assertNotNull(checkerExecutorB)
        }

    @Test
    fun `test DispatcherRegistry getTypeCheckerExecutor`() {
        val dispatcherRegistry = createDispatcherRegistry()
        // absent
        assertEquals(null, dispatcherRegistry.getTypeCheckerDispatcher("Other"))

        // present without a node resolver
        assertNull(dispatcherRegistry.getTypeCheckerDispatcher("TestType"))

        // present with a node resolver
        assertNotNull(dispatcherRegistry.getTypeCheckerDispatcher("TestNode"))
    }

    @Test
    fun `test DispatcherRegistry getNodeResolverDispatcher`() {
        val dispatcherRegistry = createDispatcherRegistry()
        // absent
        assertEquals(null, dispatcherRegistry.getNodeResolverDispatcher("Other"))

        // present without a node resolver
        assertEquals(
            null,
            dispatcherRegistry.getNodeResolverDispatcher("TestType")
        )

        // present with a node resolver
        val nodeResolver = dispatcherRegistry.getNodeResolverDispatcher("TestNode")
        assertTrue(nodeResolver != null)
    }

    @Test
    fun `test DispatcherRegistry getRequiredSelectionSet`() {
        val dispatcherRegistry = createDispatcherRegistry()
        // absent
        assertEquals(listOf<RequiredSelectionSet>(), dispatcherRegistry.getRequiredSelectionSetsForField("Missing", "missing"))

        // present with required selections
        val required = dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "parameterizedField")
        assertTrue(required.isNotEmpty())
        assertEquals(1, required.size)
        assertTrue(
            AstPrinter.printAstCompact(required[0].selections.toDocument()).contains("fragment _ on TestType")
        )
    }

    @Test
    fun `test getRequiredSelectionSet combined with field checker Rss`() {
        val dispatcherRegistry = createDispatcherRegistry()
        val rss = dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "aField")
        assertTrue(rss.isNotEmpty())
        assertEquals(1, rss.size)
        assertEquals("TestType", rss[0].selections.typeName)
        assertTrue(
            AstPrinter.printAstCompact(rss[0].selections.toDocument()).contains("{dField}")
        )
    }

    @Test
    fun `test getRequiredSelectionSetsForType includes type checker Rss`() {
        val dispatcherRegistry = createDispatcherRegistry()
        val rss = dispatcherRegistry.getRequiredSelectionSetsForType("TestNode")
        assertTrue(rss.isNotEmpty())
        assertEquals(1, rss.size)
        assertEquals("TestNode", rss[0].selections.typeName)
        assertTrue(
            AstPrinter.printAstCompact(rss[0].selections.toDocument()).contains("{id}")
        )
    }

    internal class MockValidator : Validator<ExecutorValidatorContext> {
        var arg: ExecutorValidatorContext? = null

        override fun validate(t: ExecutorValidatorContext) {
            arg = t
        }
    }

    @Test
    fun `invokes validator`() {
        MockValidator().let { validator ->
            dispatcherRegistryFactory(emptyList(), validator, MockCheckerExecutorFactory()).create(Samples.testSchema)
            assertNotNull(validator.arg)
        }
    }

    @Test
    fun `bootstraps subset of bootstrappable tenants`() {
        // Create two modules - one empty and one with resolvers
        val emptyModule = MockTenantModuleBootstrapper(Samples.testSchema) { }
        val moduleWithResolvers = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "aField", "aField")
            fieldWithValue("TestType" to "bIntField", 42)
            fieldWithValue("TestType" to "parameterizedField", true)
            fieldWithValue("TestType" to "cField", "cField")
            fieldWithValue("TestType" to "dField", "dField")
        }

        val wiring = MockValidator().let {
            dispatcherRegistryFactory(listOf(emptyModule, moduleWithResolvers), it, MockCheckerExecutorFactory()).create(Samples.testSchema) as DispatcherRegistry.Impl
        }
        assertEquals(5, wiring.fieldResolverDispatchers.size)
    }

    @Test
    fun `test node batch resolver integration`() {
        val dispatcherRegistry = createDispatcherRegistry()

        // TestBatchNode should have a node resolver (wrapped BatchingNodeResolverDispatcherImpl)
        val batchNodeResolver = dispatcherRegistry.getNodeResolverDispatcher("TestBatchNode")
        assertNotNull(batchNodeResolver)
    }

    @Test
    fun `registry entries naming off-schema coordinates are filtered out`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "aField", "aField")
        }
        val narrowerSchema = createSchemaWithWiring(
            """
                extend type Query {
                    q: String
                }
                type OtherType {
                    x: Int
                }
            """.trimIndent()
        )

        val registry = dispatcherRegistryFactory(listOf(module), Validator.Unvalidated, MockCheckerExecutorFactory()).create(narrowerSchema) as DispatcherRegistry.Impl

        assertEquals(0, registry.fieldResolverDispatchers.size)
        assertNull(registry.getFieldResolverDispatcher("TestType", "aField"))
    }

    @Test
    fun `test success creation of executors`(): Unit =
        runBlocking {
            val fieldResolverExecutors = Samples.mockTenantModule.fieldResolverExecutors.toMap()
            val nodeResolverExecutors = Samples.mockTenantModule.nodeResolverExecutors.toMap()

            assertEquals(6, fieldResolverExecutors.size)
            assertEquals(2, nodeResolverExecutors.size)

            assert(fieldResolverExecutors[("TestType" to "aField")]!! is MockFieldUnbatchedResolverExecutor)
            assert(fieldResolverExecutors[("TestType" to "bIntField")]!! is MockFieldUnbatchedResolverExecutor)
            assert(fieldResolverExecutors[("TestType" to "parameterizedField")]!! is MockFieldUnbatchedResolverExecutor)
            assert(fieldResolverExecutors[("TestType" to "batchField")]!! is MockFieldBatchResolverExecutor)
            assert(nodeResolverExecutors["TestNode"]!! is MockNodeUnbatchedResolverExecutor)
            assert(nodeResolverExecutors["TestBatchNode"]!! is MockNodeBatchResolverExecutor)
        }

    @Test
    fun `handles TenantModuleException gracefully`() {
        val throwingModule = ModuleConfigSource.from(
            InputStreamSource.fromString(
                ExecutionRegistryConfigFile.toJson(
                    ExecutionRegistryConfigFile(
                        version = "1",
                        executorFactory = ThrowingExecutorFactory::class.java.name,
                        tenantName = "test/throwing",
                        apiName = KOTLIN_API_NAME,
                        fields = listOf(
                            FieldEntryConfig(
                                typeName = "Query",
                                fieldName = "foo",
                                isBatching = false,
                                isSelective = false,
                                attribution = "throwing",
                                tenantAPIData = emptyMap(),
                            ),
                        ),
                    ),
                ),
                name = "throwing",
            ),
        )

        val workingModule: EngineTestModule = Samples.mockTenantModule.toEngineTestModule()
        val registry = StandardDispatcherRegistryFactory(
            moduleConfigSources = listOf(throwingModule, workingModule.toModuleConfigSource()),
            tenantModuleInjectorFactory = MockExecutorCodeInjector(workingModule.mockExecutorRegistry),
            validator = Validator.Unvalidated,
            checkerExecutorFactory = MockCheckerExecutorFactory(),
        ).create(Samples.testSchema) as DispatcherRegistry.Impl

        assertEquals(6, registry.fieldResolverDispatchers.size)
        assertNull(registry.getFieldResolverDispatcher("Query", "foo"))
    }

    @Test
    fun `resolver coordinate collision - last wins`() {
        val module1 = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "aField", "module1")
        }
        val module2 = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "aField", "module2")
        }

        val registry = dispatcherRegistryFactory(listOf(module1, module2), Validator.Unvalidated, MockCheckerExecutorFactory()).create(Samples.testSchema) as DispatcherRegistry.Impl

        assertEquals(1, registry.fieldResolverDispatchers.size)
        // The second module should win - verify the resolver is from module2
        val resolver = registry.getFieldResolverDispatcher("TestType", "aField")
        assertNotNull(resolver)
    }

    @Test
    fun `checker executors only created for existing resolvers`() {
        val checkerFactory = MockCheckerExecutorFactory(
            mapOf(Pair("NonExistentType", "nonExistentField") to MockCheckerExecutor()),
            mapOf(
                "NonExistentNode" to MockCheckerExecutor(),
                "TestNode" to MockCheckerExecutor() // Add checker for existing node
            )
        )

        val registry = dispatcherRegistryFactory(listOf(Samples.mockTenantModule), Validator.Unvalidated, checkerFactory).create(Samples.testSchema)

        // Should not have checker executors for non-existent resolvers
        assertNull(registry.getFieldCheckerDispatcher("NonExistentType", "nonExistentField"))
        assertNull(registry.getTypeCheckerDispatcher("NonExistentNode"))

        // But should have checker executors for existing resolvers
        assertNotNull(registry.getTypeCheckerDispatcher("TestNode"))
    }

    @Test
    fun `batch resolver wrapping in NodeResolverDispatcherImpl`() {
        val registry = dispatcherRegistryFactory(listOf(Samples.mockTenantModule), Validator.Unvalidated, MockCheckerExecutorFactory()).create(Samples.testSchema)

        val batchResolver = registry.getNodeResolverDispatcher("TestBatchNode")

        batchResolver.shouldBeInstanceOf<InstrumentedNodeResolverDispatcher>()
        val instrumentedDispatcher = batchResolver
        instrumentedDispatcher.dispatcher.shouldBeInstanceOf<NodeResolverDispatcherImpl>()
    }

    @Test
    fun `empty tenant modules handling`() {
        val registry = dispatcherRegistryFactory(emptyList(), Validator.Unvalidated, MockCheckerExecutorFactory()).create(Samples.testSchema) as DispatcherRegistry.Impl

        assertEquals(0, registry.fieldResolverDispatchers.size)
        assertEquals(0, registry.nodeResolverDispatchers.size)
    }

    @Test
    fun `multiple tenant modules with mixed resolver types`() {
        val module1 = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "aField", "aField")
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        Samples.testSchema.schema.getObjectType("TestNode"),
                        mapOf("id" to id)
                    )
                }
            }
        }

        val module2 = MockTenantModuleBootstrapper(Samples.testSchema) {
            fieldWithValue("TestType" to "bIntField", "bIntField")
            type("TestBatchNode") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                Samples.testSchema.schema.getObjectType("TestNode"),
                                mapOf("id" to selector.id)
                            )
                        )
                    }
                }
            }
        }

        val registry = dispatcherRegistryFactory(listOf(module1, module2), Validator.Unvalidated, MockCheckerExecutorFactory()).create(Samples.testSchema) as DispatcherRegistry.Impl

        // Should have both field resolvers
        assertEquals(2, registry.fieldResolverDispatchers.size)
        assertNotNull(registry.getFieldResolverDispatcher("TestType", "aField"))
        assertNotNull(registry.getFieldResolverDispatcher("TestType", "bIntField"))

        // Should have both node resolvers
        assertEquals(2, registry.nodeResolverDispatchers.size)
        assertNotNull(registry.getNodeResolverDispatcher("TestNode"))
        assertNotNull(registry.getNodeResolverDispatcher("TestBatchNode"))

        val batchNodeDispatcher = registry.getNodeResolverDispatcher("TestBatchNode")
        batchNodeDispatcher.shouldBeInstanceOf<InstrumentedNodeResolverDispatcher>()
        val instrumentedDispatcher = batchNodeDispatcher
        instrumentedDispatcher.dispatcher.shouldBeInstanceOf<NodeResolverDispatcherImpl>()
    }

    @Test
    fun `node resolver and batch resolver do not conflict`() {
        val moduleWithBoth = MockTenantModuleBootstrapper(Samples.testSchema) {
            // Regular node resolver
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        Samples.testSchema.schema.getObjectType("TestNode"),
                        mapOf("id" to id, "type" to "regular")
                    )
                }
            }
            // Batch node resolver for different type
            type("TestBatchNode") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector ->
                        Result.success(
                            createEngineObjectData(
                                Samples.testSchema.schema.getObjectType("TestNode"),
                                mapOf("id" to selector.id, "type" to "batch")
                            )
                        )
                    }
                }
            }
        }

        val registry = dispatcherRegistryFactory(listOf(moduleWithBoth), Validator.Unvalidated, MockCheckerExecutorFactory()).create(Samples.testSchema)

        // Should have both resolvers
        val regularResolver = registry.getNodeResolverDispatcher("TestNode")
        val batchResolver = registry.getNodeResolverDispatcher("TestBatchNode")

        assertNotNull(regularResolver)
        assertNotNull(batchResolver)
    }

    @Test
    fun `do not register checkers for introspection types or fields`() {
        val checkerFactory = MockCheckerExecutorFactory()

        val registry = dispatcherRegistryFactory(listOf(Samples.mockTenantModule), Validator.Unvalidated, checkerFactory).create(Samples.testSchema)

        // Introspection type and fields should not have checkers registered
        assertNull(registry.getTypeCheckerDispatcher("__Schema"))
        assertNull(registry.getFieldCheckerDispatcher("__Type", "kind"))
        assertNull(registry.getFieldCheckerDispatcher("__Field", "name"))
        assertNull(registry.getFieldCheckerDispatcher("TestType", "__typename"))
        assertNull(registry.getFieldCheckerDispatcher("Query", "__typename"))
        assertNull(registry.getFieldCheckerDispatcher("Query", "__schema"))
    }

    @Test
    fun `proxyField executor replaces original field executor at bootstrap`() {
        val proxyFactory = object : ProxyResolverFactory {
            override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor =
                object : FieldResolverExecutor by executor {
                    override val metadata = ResolverMetadata.forMock("proxied:${executor.metadata.name}")
                }

            override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? = null
        }

        val registry = dispatcherRegistryFactory(
            modules,
            Validator.Unvalidated,
            checkerExecutorFactory,
            proxyResolverFactory = proxyFactory
        ).create(Samples.testSchema) as DispatcherRegistry.Impl

        assertTrue(registry.fieldResolverDispatchers.values.isNotEmpty())
        assertTrue(registry.fieldResolverDispatchers.values.all { it.resolverMetadata.name.startsWith("proxied:") })
    }

    @Test
    fun `proxyNode executor replaces original node executor at bootstrap`() {
        val proxyFactory = object : ProxyResolverFactory {
            override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? = null

            override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor =
                object : NodeResolverExecutor by executor {
                    override val metadata = ResolverMetadata.forMock("proxied:${executor.metadata.name}")
                }
        }

        val registry = dispatcherRegistryFactory(
            modules,
            Validator.Unvalidated,
            checkerExecutorFactory,
            proxyResolverFactory = proxyFactory
        ).create(Samples.testSchema) as DispatcherRegistry.Impl

        assertTrue(registry.nodeResolverDispatchers.values.isNotEmpty())
        assertTrue(registry.nodeResolverDispatchers.values.all { it.resolverMetadata.name.startsWith("proxied:") })
    }

    @Test
    fun `DispatcherRegistryFactory finalizes the injector factory even with no config sources`() {
        val injectorFactory = RecordingTenantModuleInjectorFactory()

        StandardDispatcherRegistryFactory(
            moduleConfigSources = emptyList(),
            tenantModuleInjectorFactory = injectorFactory,
            validator = Validator.Unvalidated,
            checkerExecutorFactory = checkerExecutorFactory,
        ).create(Samples.testSchema)

        assertEquals(0, injectorFactory.bootstrapCalls)
        assertEquals(1, injectorFactory.onBootstrapCompleteCalls)
    }
}

/** Records lifecycle calls so tests can assert the [TenantModuleInjectorFactory] SPI contract. */
private class RecordingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
    var bootstrapCalls: Int = 0
    var onBootstrapCompleteCalls: Int = 0

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector {
        bootstrapCalls += 1
        return CodeInjector.Naive
    }

    override suspend fun onBootstrapComplete() {
        onBootstrapCompleteCalls += 1
    }
}

/** Reflectively constructed by the engine, hence the 2-arg constructor. */
class ThrowingExecutorFactory(
    @Suppress("UNUSED_PARAMETER") codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor = throw TenantModuleException("Test exception")

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor = throw TenantModuleException("Test exception")
}
