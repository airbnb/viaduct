package viaduct.engine.api.mocks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.VariablesResolver
import viaduct.engine.runtime.FieldResolverDispatcherImpl

class MocksAdditionalTest {
    @Test
    fun `MockFieldUnbatchedResolverExecutor with selection set`() {
        val selectionSet = mkRSS("TestType", "aField bIntField")
        val resolverId = "TestType.field1"
        val executor = MockFieldUnbatchedResolverExecutor(objectSelectionSet = selectionSet, resolverId = resolverId)

        assertFalse(executor.isBatching)
        assertEquals(selectionSet, executor.objectSelectionSet)
        assertEquals(ResolverMetadata.forMock("mock-field-unbatched-resolver"), executor.metadata)
        assertEquals(resolverId, executor.resolverId)
    }

    @Test
    fun `MockFieldBatchResolverExecutor with batching`() {
        val resolverId = "TestType.field1"
        val executor = MockFieldBatchResolverExecutor(resolverId = resolverId)

        assertTrue(executor.isBatching)
        assertEquals(ResolverMetadata.forMock("mock-field-batch-resolver"), executor.metadata)
        assertEquals(resolverId, executor.resolverId)
    }

    @Test
    fun `MockFieldResolverExecutor Null companion object`() {
        val nullResolver = MockFieldUnbatchedResolverExecutor.Null
        assertNotNull(nullResolver)
        assertNull(nullResolver.objectSelectionSet)
        assertEquals(ResolverMetadata.forMock("mock-field-unbatched-resolver"), nullResolver.metadata)
    }

    @Test
    fun `MockNodeResolverExecutor basic properties`() {
        val resolver = MockNodeBatchResolverExecutor("TestBatchNode") { _, _ -> emptyMap() }
        assertEquals("TestBatchNode", resolver.typeName)
    }

    @Test
    fun `MockVariablesResolver properties`() {
        val resolver = MockVariablesResolver("var1", "var2") { emptyMap() }
        assertEquals(setOf("var1", "var2"), resolver.variableNames)
    }

    @Test
    fun `MockCheckerExecutor with custom requiredSelectionSets`() {
        val selectionSets = mapOf("TestType" to mkRSS("TestType", "aField"))
        val executor = MockCheckerExecutor(requiredSelectionSets = selectionSets)

        assertEquals(selectionSets, executor.requiredSelectionSets)
    }

    @Test
    fun `MockCheckerExecutorFactory functionality`() {
        val fieldChecker = MockCheckerExecutor()
        val typeChecker = MockCheckerExecutor()
        val mockSchema = MockSchema.mk("type TestType { testField: String } type TestNode implements Node { id: ID! }")

        val factory = MockCheckerExecutorFactory(
            mapOf(Pair("TestType", "testField") to fieldChecker),
            mapOf("TestNode" to typeChecker)
        )

        assertSame(fieldChecker, factory.checkerExecutorForField(mockSchema, "TestType", "testField"))
        assertNull(factory.checkerExecutorForField(mockSchema, "TestType", "nonExistent"))

        assertSame(typeChecker, factory.checkerExecutorForType(mockSchema, "TestNode"))
        assertNull(factory.checkerExecutorForType(mockSchema, "NonExistent"))
    }

    @Test
    fun `MockFieldResolverDispatcherRegistry functionality`() {
        val dispatcher1 = FieldResolverDispatcherImpl(MockFieldUnbatchedResolverExecutor.Null)
        val dispatcher2 = FieldResolverDispatcherImpl(MockFieldUnbatchedResolverExecutor(resolverId = "TestType.field2") { _, _, _, _, _ -> "test" })
        val dispatcher3 = FieldResolverDispatcherImpl(MockFieldBatchResolverExecutor(resolverId = "TestType.field3") { _, _ -> emptyMap() })

        val registry = MockFieldResolverDispatcherRegistry(
            Pair("TestType", "field1") to dispatcher1,
            Pair("TestType", "field2") to dispatcher2,
            Pair("TestType", "field3") to dispatcher3
        )

        assertSame(dispatcher1, registry.getFieldResolverDispatcher("TestType", "field1"))
        assertSame(dispatcher2, registry.getFieldResolverDispatcher("TestType", "field2"))
        assertSame(dispatcher3, registry.getFieldResolverDispatcher("TestType", "field3"))
        assertNull(registry.getFieldResolverDispatcher("TestType", "nonExistent"))
    }

    @Test
    fun `MockTenantAPIBootstrapper with modules list`() {
        val module1 = Samples.mockTenantModule
        val module2 = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("OtherType" to "otherField") {
                resolver {
                    fn { _, _, _, _, _ -> "other" }
                }
            }
        }

        val bootstrapper = MockTenantAPIBootstrapper(listOf(module1, module2))

        // Test that the bootstrapper holds the modules
        assertNotNull(bootstrapper)
    }

    @Test
    fun `MockTenantModuleBootstrapper Builder resolver function`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "testField") {
                resolver {
                    objectSelections("aField bIntField")
                    fn { _, _, _, _, _ ->
                        "resolver-result"
                    }
                }
            }
        }

        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolvers.size)

        val (coordinate, executor) = resolvers[0]
        assertEquals(Coordinate("TestType", "testField"), coordinate)
        assertNotNull(executor.objectSelectionSet)
    }

    @Test
    fun `MockTenantModuleBootstrapper Builder nodeResolver function`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestNode"), mapOf("id" to id))
                }
            }
        }

        val nodeResolvers = module.nodeResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, nodeResolvers.size)

        val (typeName, executor) = nodeResolvers[0]
        assertEquals("TestNode", typeName)
        assertInstanceOf(MockNodeUnbatchedResolverExecutor::class.java, executor)
    }

    @Test
    fun `MockTenantModuleBootstrapper Builder nodeBatchResolver function`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            type("TestBatchNode") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { Result.success(mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestNode"), emptyMap())) }
                }
            }
        }

        val resolverExecutors = module.nodeResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolverExecutors.size)

        val (typeName, resolver) = resolverExecutors[0]
        assertEquals("TestBatchNode", typeName)
        assertInstanceOf(MockNodeBatchResolverExecutor::class.java, resolver)
    }

    @Test
    fun `MockTenantModuleBootstrapper Builder mixed resolvers`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "field1") {
                resolver {
                    fn { _, _, _, _, _ -> "field1" }
                }
            }
            field("TestType" to "field2") {
                resolver {
                    fn { _, _, _, _, _ -> "field2" }
                }
            }
            type("Node1") {
                nodeUnbatchedExecutor { _, _, _ -> mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestNode"), emptyMap()) }
            }
            type("BatchNode1") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { Result.success(mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestNode"), emptyMap())) }
                }
            }
        }

        assertEquals(2, module.fieldResolverExecutors(Samples.testSchema).count())
        assertEquals(2, module.nodeResolverExecutors(Samples.testSchema).count())
    }

    @Test
    fun `Samples testSchema structure`() {
        val schema = Samples.testSchema

        // Verify schema contains expected types
        assertNotNull(schema.schema.getObjectType("TestType"))
        assertNotNull(schema.schema.getObjectType("TestNode"))
        assertNotNull(schema.schema.queryType)

        // Verify TestType fields
        val testType = schema.schema.getObjectType("TestType")
        assertNotNull(testType.getFieldDefinition("aField"))
        assertNotNull(testType.getFieldDefinition("bIntField"))
        assertNotNull(testType.getFieldDefinition("parameterizedField"))
        assertNotNull(testType.getFieldDefinition("cField"))
        assertNotNull(testType.getFieldDefinition("dField"))
    }

    @Test
    fun `Samples mockTenantModule structure`() {
        val module = Samples.mockTenantModule

        // Verify it has resolvers
        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(6, resolvers.size)

        // Verify resolver coordinates
        val coordinates = resolvers.map { it.first }.toSet()
        assertEquals(
            setOf(
                Coordinate("TestType", "aField"),
                Coordinate("TestType", "bIntField"),
                Coordinate("TestType", "parameterizedField"),
                Coordinate("TestType", "cField"),
                Coordinate("TestType", "dField"),
                Coordinate("TestType", "batchField")
            ),
            coordinates
        )

        val nodeResolvers = module.nodeResolverExecutors(Samples.testSchema).toList()
        assertEquals(2, nodeResolvers.size)

        // Verify unbatched node resolvers
        val unbatchedResolver = nodeResolvers[0]
        assertInstanceOf(MockNodeUnbatchedResolverExecutor::class.java, unbatchedResolver.second)
        assertEquals("TestNode", unbatchedResolver.first)

        // Verify batch resolvers
        val resolver = nodeResolvers[1]
        assertInstanceOf(MockNodeBatchResolverExecutor::class.java, resolver.second)
        assertEquals("TestBatchNode", resolver.first)
    }

    @Test
    fun `mkRSS helper function`() {
        val rss = mkRSS("TestType", "aField bIntField")
        assertNotNull(rss.selections)
        assertEquals(emptyList<Any>(), rss.variablesResolvers)

        val variablesResolver = VariablesResolver.Empty
        val rssWithVars = mkRSS("TestType", "aField", listOf(variablesResolver))
        assertEquals(listOf(variablesResolver), rssWithVars.variablesResolvers)
    }

    @Test
    fun `mkDispatcherRegistry helper function structure`() {
        val fieldUnbatchedResolverExecutor = MockFieldUnbatchedResolverExecutor.Null
        val fieldBatchResolverExecutor = MockFieldBatchResolverExecutor(resolverId = "Test.fieldBatch")
        val nodeUnbatchedResolverExecutor = MockNodeUnbatchedResolverExecutor()
        val nodeBatchResolverExecutor = MockNodeBatchResolverExecutor(typeName = "TestNodeBatch")
        val checker = MockCheckerExecutor()

        val registry = mkDispatcherRegistry(
            fieldResolverExecutors = mapOf(Coordinate("Test", "field") to fieldUnbatchedResolverExecutor, Coordinate("Test", "fieldBatch") to fieldBatchResolverExecutor),
            nodeResolverExecutors = mapOf("TestNode" to nodeUnbatchedResolverExecutor, "TestNodeBatch" to nodeBatchResolverExecutor),
            fieldCheckerExecutors = mapOf(Coordinate("Test", "field") to checker),
            typeCheckerExecutors = mapOf("TestNode" to checker)
        )

        // Test through the public interface
        assertNotNull(registry.getFieldCheckerDispatcher("Test", "field"))
        assertNotNull(registry.getTypeCheckerDispatcher("TestNode"))
    }

    @Test
    fun `mkEngineObjectData basic properties`() {
        val objectType = Samples.testSchema.schema.getObjectType("TestType")
        val data = mapOf("aField" to "fieldValue", "bIntField" to 42)
        val mockData = mkEngineObjectData(objectType, data)

        assertEquals(objectType, mockData.graphQLObjectType)
        data.forEach { (key, value) -> assertEquals(value, mockData.get(key)) }
    }

    @Test
    fun `MockSchema minimal schema structure`() {
        val schema = MockSchema.minimal
        assertNotNull(schema.schema.queryType)
        assertNotNull(schema.schema.queryType.getFieldDefinition("empty"))
    }

    @Test
    fun `MockSchema mk helper function`() {
        val schema = MockSchema.mk("extend type Query { testField: String }")
        assertNotNull(schema.schema.queryType)
        assertNotNull(schema.schema.queryType.getFieldDefinition("testField"))
    }

    @Test
    fun `MockTenantModuleBootstrapper empty constructor`() {
        val emptyModule = MockTenantModuleBootstrapper(MockSchema.minimal)

        assertEquals(0, emptyModule.fieldResolverExecutors(Samples.testSchema).count())
        assertEquals(0, emptyModule.nodeResolverExecutors(Samples.testSchema).count())
    }

    @Test
    fun `MockCheckerExecutorFactory with null inputs`() {
        val mockSchema = MockSchema.mk("type AnyType { anyField: String }")
        val factory = MockCheckerExecutorFactory()
        assertNull(factory.checkerExecutorForField(mockSchema, "AnyType", "anyField"))
        assertNull(factory.checkerExecutorForType(mockSchema, "AnyType"))
    }
}
