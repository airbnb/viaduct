@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData

class MockTenantModuleBootstrapperDSLTest {
    companion object {
        private val emptyArgs = emptyMap<String, Any?>()
        private val emptyObjectMap = emptyMap<String, EngineObjectData>()

        private const val SCHEMA_SDL = """
            extend type Query {
              t: Test
            }

            type Test implements Node {
              id: ID!
              i: Int
              j: Int
              k: Int
            }
            """
    }

    @Test
    fun `field with value without fieldWithValue`() {
        val coord = "Test" to "k"
        val module = MockTenantModuleBootstrapper(SCHEMA_SDL) {
            field(coord) { value(42) }
        }
        assertEquals(42, module.resolveField(coord))
    }

    @Test
    fun `field with valueFromContext`() {
        val coord = "Query" to "t"
        val module = MockTenantModuleBootstrapper(SCHEMA_SDL) {
            field(coord) {
                valueFromContext { ctx ->
                    ctx.createNodeReference("123", schema.schema.getObjectType("Test"))
                }
            }
        }
        assertInstanceOf(NodeEngineObjectData::class.java, module.resolveField(coord))
    }

    @Test
    fun `field checker succeeds`() {
        var iRan = false
        val coord = "Query" to "t"
        val module = MockTenantModuleBootstrapper(SCHEMA_SDL) {
            field(coord) {
                checkerExecutor {
                    MockCheckerExecutor { _, _ -> iRan = true }
                }
            }
        }
        assertSame(CheckerResult.Success, module.checkField(coord))
        assertTrue(iRan)
    }

    @Test
    fun `field checker fails`() {
        var myException: Exception? = null
        val coord = "Query" to "t"
        val module = MockTenantModuleBootstrapper(SCHEMA_SDL) {
            field(coord) {
                checkerExecutor {
                    MockCheckerExecutor { _, _ ->
                        try {
                            throw SecurityException()
                        } catch (e: Exception) {
                            myException = e
                            throw e
                        }
                    }
                }
            }
        }
        val result = module.checkField(coord)
        assertInstanceOf(MockCheckerErrorResult::class.java, result)
        assertNotNull(myException)
        assertSame(myException, (result as MockCheckerErrorResult).error)
    }

    @Test
    fun `field checker with objectSelections adds to checkerExecutors map`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                checker {
                    objectSelections("testObj", "fragment _ on TestType { bIntField cField }")
                    fn { _, _ -> }
                }
            }
            field("TestType" to "cField") {
                checker {
                    fn { _, _ -> noAccess() }
                }
            }
        }

        // Verify checker was added to an external map
        assertEquals(2, module.checkerExecutors.size)
        assertTrue(module.checkerExecutors.containsKey(Coordinate("TestType", "aField")))
        assertTrue(module.checkerExecutors.containsKey(Coordinate("TestType", "cField")))

        val (ctx, reg) = module.contextMocks.run { Pair(engineExecutionContext, dispatcherRegistry) }
        runBlocking {
            val result1 = reg.getFieldCheckerDispatcher("TestType", "aField")!!.execute(emptyArgs, emptyObjectMap, ctx, viaduct.engine.api.CheckerExecutor.CheckerType.FIELD)
            assertEquals(CheckerResult.Success, result1)

            val result2 = reg.getFieldCheckerDispatcher("TestType", "cField")!!.execute(emptyArgs, emptyObjectMap, ctx, viaduct.engine.api.CheckerExecutor.CheckerType.FIELD)
            assertEquals(true, result2 is CheckerResult.Error)
            assertEquals(true, (result2 as CheckerResult.Error).error is SecurityException)
        }
    }

    @Test
    fun `field checker with multiple objectSelections`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                checker {
                    objectSelections("obj1", "fragment _ on TestType { aField }")
                    objectSelections("obj2", "fragment _ on TestType { bIntField }")
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }

        assertEquals(1, module.checkerExecutors.size)
        val checker = module.checkerExecutors[Coordinate("TestType", "aField")]
        assertNotNull(checker)
        // The checker should have both selection sets
        assertEquals(2, checker!!.requiredSelectionSets.size)
    }

    @Test
    fun `node checker with objectSelections adds to typeCheckerExecutors map`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            type("TestNode") {
                checker {
                    objectSelections("nodeObj", "fragment _ on TestNode { id }")
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }

        // Verify checker was added to an external map
        assertEquals(1, module.typeCheckerExecutors.size)
        assertTrue(module.typeCheckerExecutors.containsKey("TestNode"))
    }

    @Test
    fun `resolver with metadata configuration`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                resolver {
                    resolverName("metadata-resolver-name")
                    fn { _, _, _, _, _ -> "metadata-result" }
                }
            }
        }

        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolvers.size)
        val executor = resolvers[0].second
        assertEquals("mock:metadata-resolver-name", executor.metadata.toTagString())

        // Execute the resolver and verify it works
        val (ctx, reg) = module.contextMocks.run { Pair(engineExecutionContext, dispatcherRegistry) }
        val testObject = mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestType"), emptyMap())
        val testQuery = mkEngineObjectData(Samples.testSchema.schema.getObjectType("Query"), emptyMap())
        runBlocking {
            val result = reg.getFieldResolverDispatcher("TestType", "aField")!!.resolve(
                emptyArgs,
                testObject,
                testQuery,
                { testObject },
                { testQuery },
                null,
                ctx
            )
            assertEquals("metadata-result", result)
        }
    }

    @Test
    fun `resolver with variables and metadata and objectSelections`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                resolver {
                    objectSelections("fragment _ on TestType { aField bIntField }") {
                        variables("testVar") { ctx -> mapOf("testVar" to ctx.arguments["input"]) }
                    }
                    resolverName("test-resolver")
                    fn { _, _, _, _, _ -> "complex-result" }
                }
            }
        }

        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolvers.size)
        val executor = resolvers[0].second
        assertEquals("mock:test-resolver", executor.metadata.toTagString())
        assertNotNull(executor.objectSelectionSet)

        // Execute the resolver and verify it works
        val (ctx, reg) = module.contextMocks.run { Pair(engineExecutionContext, dispatcherRegistry) }
        val testObject = mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestType"), emptyMap())
        val testQuery = mkEngineObjectData(Samples.testSchema.schema.getObjectType("Query"), emptyMap())
        runBlocking {
            val result = reg.getFieldResolverDispatcher("TestType", "aField")!!.resolve(
                mapOf("input" to "test-input"),
                testObject,
                testQuery,
                { testObject },
                { testQuery },
                null,
                ctx
            )
            assertEquals("complex-result", result)
        }
    }

    @Test
    fun `duplicate fieldWithValue calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                fieldWithValue("TestType" to "aField", "value1")
                fieldWithValue("TestType" to "aField", "value2")
            }
        }
    }

    @Test
    fun `duplicate field resolver calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                field("TestType" to "aField") {
                    resolver { fn { _, _, _, _, _ -> "result1" } }
                }
                field("TestType" to "aField") {
                    resolver { fn { _, _, _, _, _ -> "result2" } }
                }
            }
        }
    }

    @Test
    fun `duplicate node resolver calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                type("TestNode") {
                    nodeUnbatchedExecutor { _, _, _ -> mkEngineObjectData(objectType, emptyMap()) }
                }
                type("TestNode") {
                    nodeUnbatchedExecutor { _, _, _ -> mkEngineObjectData(objectType, emptyMap()) }
                }
            }
        }
    }

    @Test
    fun `duplicate node batchResolver calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                type("TestNode") {
                    nodeBatchedExecutor { selectors, _ ->
                        selectors.associateWith { Result.success(mkEngineObjectData(objectType, emptyMap())) }
                    }
                }
                type("TestNode") {
                    nodeBatchedExecutor { selectors, _ ->
                        selectors.associateWith { Result.success(mkEngineObjectData(objectType, emptyMap())) }
                    }
                }
            }
        }
    }

    @Test
    fun `duplicate field checker calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                field("TestType" to "aField") {
                    checker { fn { _, _ -> /* validation1 */ } }
                    checker { fn { _, _ -> /* validation2 */ } }
                }
            }
        }
    }

    @Test
    fun `duplicate node checker calls throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockTenantModuleBootstrapper(Samples.testSchema) {
                type("TestNode") {
                    checker { fn { _, _ -> /* validation1 */ } }
                    checker { fn { _, _ -> /* validation2 */ } }
                }
            }
        }
    }

    @Test
    fun `FieldScope properties access`() {
        var capturedSchema: Any? = null
        var capturedType: Any? = null
        var capturedFieldType: Any? = null
        var capturedCoord: Any? = null

        MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                capturedSchema = schema
                capturedType = objectType
                capturedFieldType = fieldType
                capturedCoord = coord

                resolver { fn { _, _, _, _, _ -> "test" } }
            }
        }

        assertEquals(Samples.testSchema, capturedSchema)
        assertEquals(Samples.testSchema.schema.getObjectType("TestType"), capturedType)
        assertEquals(Coordinate("TestType", "aField"), capturedCoord)
        assertNotNull(capturedFieldType)
    }

    @Test
    fun `field with both resolver and checker`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                resolver {
                    fn { _, _, _, _, _ -> "resolved-value" }
                }
                checker {
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }

        // Verify both resolver and checker were created
        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolvers.size)
        assertEquals(1, module.checkerExecutors.size)
    }

    @Test
    fun `node with both resolver and checker`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(objectType, mapOf("id" to id))
                }
                checker {
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }

        val nodeExecutors = module.nodeResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, nodeExecutors.size)
        assertEquals(1, module.typeCheckerExecutors.size)

        // Execute the node resolver and verify it works
        val (ctx, reg) = module.contextMocks.run { Pair(engineExecutionContext, dispatcherRegistry) }
        runBlocking {
            val selectionSet = ctx.rawSelectionSetFactory.rawSelectionSet("TestNode", "id", emptyMap())
            val result = reg.getNodeResolverDispatcher("TestNode")!!.resolve("test-id", selectionSet, ctx)
            assertEquals("test-id", result.fetch("id"))

            // Execute the checker and verify it works
            val checkerResult = reg.getTypeCheckerDispatcher("TestNode")!!.execute(emptyArgs, emptyObjectMap, ctx, viaduct.engine.api.CheckerExecutor.CheckerType.TYPE)
            assertEquals(CheckerResult.Success, checkerResult)
        }
    }

    @Test
    fun `resolver without objectSelections has null selection set`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            field("TestType" to "aField") {
                resolver {
                    fn { _, _, _, _, _ -> "simple-result" }
                }
            }
        }

        val resolvers = module.fieldResolverExecutors(Samples.testSchema).toList()
        assertEquals(1, resolvers.size)
        val executor = resolvers[0].second
        // objectSelectionSet should be null when no objectSelections() is called
        assertEquals(null, executor.objectSelectionSet)

        // Execute the resolver and verify it works
        val (ctx, reg) = module.contextMocks.run { Pair(engineExecutionContext, dispatcherRegistry) }
        val testObject = mkEngineObjectData(Samples.testSchema.schema.getObjectType("TestType"), emptyMap())
        val testQuery = mkEngineObjectData(Samples.testSchema.schema.getObjectType("Query"), emptyMap())
        runBlocking {
            val result = reg.getFieldResolverDispatcher("TestType", "aField")!!.resolve(
                emptyArgs,
                testObject,
                testQuery,
                { testObject },
                { testQuery },
                null,
                ctx
            )
            assertEquals("simple-result", result)
        }
    }

    @Test
    fun `complex DSL combination`() {
        val module = MockTenantModuleBootstrapper(Samples.testSchema) {
            // Simple field with value
            fieldWithValue("TestType" to "simpleField", "simple")

            // Complex field with resolver and checker
            field("TestType" to "complexField") {
                resolver {
                    objectSelections("fragment _ on TestType { aField bIntField }") {
                        variables("param") { ctx -> mapOf("param" to ctx.arguments["input"]) }
                    }
                    resolverName("complex-resolver")
                    fn { _, _, _, _, _ -> "complex-result" }
                }
                checker {
                    objectSelections("validation", "fragment _ on TestType { aField }")
                    fn { _, _ -> /* validation */ }
                }
            }

            // Node with resolver
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(objectType, mapOf("id" to id))
                }
            }

            // Node with batch resolver and checker
            type("BatchNode") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { selector -> Result.success(mkEngineObjectData(objectType, mapOf("id" to selector.id))) }
                }
                checker {
                    objectSelections("batch", "fragment _ on BatchNode { id }")
                    fn { _, _ -> /* batch validation */ }
                }
            }
        }

        // Verify resolver/node counts
        assertEquals(2, module.fieldResolverExecutors(Samples.testSchema).count())
        assertEquals(2, module.nodeResolverExecutors(Samples.testSchema).count())

        // Verify checkers were accumulated in external maps
        assertEquals(1, module.checkerExecutors.size) // complexField checker
        assertEquals(1, module.typeCheckerExecutors.size) // BatchNode checker
    }

    @Test
    fun `schema is copied to ContextMocks`() {
        val sdl = "extend type Query {x:Int}"
        val schema = mkSchema(sdl)

        MockTenantModuleBootstrapper(schema) {}
            .contextMocks
            .let { mocks ->
                assertSame(schema, mocks.fullSchema)
            }
    }
}
