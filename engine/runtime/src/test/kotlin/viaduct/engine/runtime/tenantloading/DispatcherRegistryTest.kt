@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.tenantloading

import graphql.language.AstPrinter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.collections.count
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.LoggerFactory.getLogger
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.TenantModuleException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.Samples
import viaduct.engine.api.mocks.mkSchemaWithWiring
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.validation.Validator

@ExperimentalCoroutinesApi
class DispatcherRegistryTest {
    private lateinit var bootstrapper: TenantAPIBootstrapper
    private lateinit var checkerExecutorFactory: MockCheckerExecutorFactory

    @BeforeEach
    fun setUp() {
        bootstrapper = MockTenantAPIBootstrapper(listOf(Samples.mockTenantModule))

        checkerExecutorFactory = MockCheckerExecutorFactory(
            mapOf(
                Pair("TestType", "aField") to MockCheckerExecutor(
                    requiredSelectionSets = mapOf(
                        "checker_0" to RequiredSelectionSet(
                            SelectionsParser.parse("TestType", "dField"),
                            emptyList()
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
                            emptyList()
                        )
                    )
                )
            )
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun createDispatcherRegistry() = DispatcherRegistryFactory(bootstrapper, Validator.Unvalidated, checkerExecutorFactory).create(Samples.testSchema)

    @Test
    fun `test successful injection of dispatcher`(): Unit =
        runBlocking {
            val dispatcherRegistry = createDispatcherRegistry()
            // We have 6 resolvers: aField, bIntField, parameterizedField, cField, dField, batchField
            assertEquals(6, dispatcherRegistry.get().count())

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
        assertEquals(listOf<RequiredSelectionSet>(), dispatcherRegistry.getRequiredSelectionSetsForField("Missing", "missing", true))
        assertEquals(listOf<RequiredSelectionSet>(), dispatcherRegistry.getRequiredSelectionSetsForField("Missing", "missing", false))

        // present with required selections
        val required = dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "parameterizedField", true)
        assertTrue(required.isNotEmpty())
        assertEquals(1, required.size)
        assertTrue(
            AstPrinter
                .printAstCompact(required[0].selections.toDocument())
                .contains("fragment _ on TestType")
        )
        assertEquals(required, dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "parameterizedField", false))
    }

    @Test
    fun `test getRequiredSelectionSet combined with field checker Rss when executeAccessChecksInModstrat is true`() {
        val dispatcherRegistry = createDispatcherRegistry()
        val rss = dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "aField", true)
        assertTrue(rss.isNotEmpty())
        assertEquals(1, rss.size)
        assertEquals("TestType", rss[0].selections.typeName)
        assertTrue(
            AstPrinter
                .printAstCompact(rss[0].selections.toDocument())
                .contains("{dField}")
        )
    }

    @Test
    fun `test getRequiredSelectionSet combined with field checker Rss when executeAccessChecksInModstrat is off but checker is on modern field`() {
        val dispatcherRegistry = createDispatcherRegistry()
        val rss = dispatcherRegistry.getRequiredSelectionSetsForField("TestType", "aField", false)
        assertTrue(rss.isNotEmpty())
        assertEquals(1, rss.size)
        assertEquals("TestType", rss[0].selections.typeName)
        assertTrue(
            AstPrinter
                .printAstCompact(rss[0].selections.toDocument())
                .contains("{dField}")
        )
    }

    @Test
    fun `test getRequiredSelectionSetsForType with executeAccessChecksInModstrat off`() {
        val dispatcherRegistry = createDispatcherRegistry()
        assertTrue(dispatcherRegistry.getRequiredSelectionSetsForType("TestNode", false).isEmpty())
    }

    @Test
    fun `test getRequiredSelectionSetsForType with executeAccessChecksInModstrat on`() {
        val dispatcherRegistry = createDispatcherRegistry()
        val rss = dispatcherRegistry.getRequiredSelectionSetsForType("TestNode", true)
        assertTrue(rss.isNotEmpty())
        assertEquals(1, rss.size)
        assertEquals("TestNode", rss[0].selections.typeName)
        assertTrue(
            AstPrinter
                .printAstCompact(rss[0].selections.toDocument())
                .contains("{id}")
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
        val bootstrapper = MockTenantAPIBootstrapper(emptyList())
        MockValidator().let { validator ->
            DispatcherRegistryFactory(bootstrapper, validator, MockCheckerExecutorFactory()).create(Samples.testSchema)
            assertNotNull(validator.arg)
        }
    }

    @Test
    fun `bootstraps subset of bootstrappable tenants`() {
        // Create two modules - one empty and one with resolvers
        val emptyModule = MockTenantModuleBootstrapper(Samples.testSchema) { }
        val moduleWithResolvers = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                resolver {
                    fn { _, _, _, _, _ -> "aField" }
                }
            }
            field("TestType" to "bIntField") {
                resolver {
                    fn { _, _, _, _, _ -> 42 }
                }
            }
            field("TestType" to "parameterizedField") {
                resolver {
                    fn { _, _, _, _, _ -> true }
                }
            }
            field("TestType" to "cField") {
                resolver {
                    fn { _, _, _, _, _ -> "cField" }
                }
            }
            field("TestType" to "dField") {
                resolver {
                    fn { _, _, _, _, _ -> "dField" }
                }
            }
        }

        val bootstrapper = MockTenantAPIBootstrapper(listOf(emptyModule, moduleWithResolvers))
        val wiring = MockValidator().let {
            DispatcherRegistryFactory(bootstrapper, it, MockCheckerExecutorFactory()).create(Samples.testSchema)
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
    @Suppress("DEPRECATION")
    fun `errors when schema mismatches tenants`() {
        val expectedSchema = Samples.testSchema
        val mismatchedSchema = mkSchemaWithWiring(
            """
                extend type Query {
                    q: String
                }
                type OtherType {
                    x: Int
                }
            """.trimIndent()
        )

        class MismatchThrowingBootstrapper(private val expected: ViaductSchema) : TenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> {
                if (schema !== expected) throw TenantModuleException("Schema mismatch in tenant bootstrapper")
                return emptyList()
            }

            override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> {
                if (schema !== expected) throw TenantModuleException("Schema mismatch in tenant bootstrapper")
                return emptyList()
            }
        }

        val bootstrapper = MismatchThrowingBootstrapper(expectedSchema)
        val exception1 = assertThrows(TenantModuleException::class.java) {
            bootstrapper.fieldResolverExecutors(mismatchedSchema)
        }
        assertTrue(exception1.message!!.contains("Schema mismatch"))

        val exception2 = assertThrows(TenantModuleException::class.java) {
            bootstrapper.nodeResolverExecutors(mismatchedSchema)
        }
        assertTrue(exception2.message!!.contains("Schema mismatch"))
    }

    @Test
    fun `test success creation of executors`(): Unit =
        runBlocking {
            val tenantModuleBootstrappers = bootstrapper.tenantModuleBootstrappers().toList()
            assertEquals(1, tenantModuleBootstrappers.size)

            val tenantModuleBootstrapper = tenantModuleBootstrappers[0]
            val fieldResolverExecutors = tenantModuleBootstrapper.fieldResolverExecutors(Samples.testSchema).toMap()
            val nodeResolverExecutors = tenantModuleBootstrapper.nodeResolverExecutors(Samples.testSchema).toMap()

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
    fun `should log warning when registry is empty with non-contributing modern bootstrappers`() {
        class ViaductTenantModuleBootstrapper : TenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema) = emptyList<Pair<Coordinate, FieldResolverExecutor>>()

            override fun nodeResolverExecutors(schema: ViaductSchema) = emptyList<Pair<String, NodeResolverExecutor>>()
        }

        mockkStatic(LoggerFactory::class)
        val mockLogger = mockk<Logger>(relaxed = true)
        every { getLogger(any<Class<*>>()) } returns mockLogger
        every { getLogger(any<String>()) } returns mockLogger
        val logPrefix = "Empty executor registry for "

        assertDoesNotThrow {
            val dispatcherRegistry = DispatcherRegistryFactory(
                MockTenantAPIBootstrapper(listOf(ViaductTenantModuleBootstrapper())),
                Validator.Unvalidated,
                MockCheckerExecutorFactory()
            ).create(Samples.testSchema)

            assertTrue(dispatcherRegistry.isEmpty())
            assertEquals(0, dispatcherRegistry.fieldResolverDispatchers.size)
            assertEquals(0, dispatcherRegistry.nodeResolverDispatchers.size)
            assertEquals(0, dispatcherRegistry.fieldCheckerDispatchers.size)
            assertEquals(0, dispatcherRegistry.typeCheckerDispatchers.size)
        }

        verify(atLeast = 1) { mockLogger.warn(match<String> { it.startsWith(logPrefix) }, any<Any>()) }
    }
}
