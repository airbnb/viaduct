@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Coordinate

class EngineTestModuleTest {
    companion object {
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
    fun `DSL builds EngineTestModule with field resolvers`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            field("Test" to "i") {
                resolver { fn { _, _, _, _, _ -> 1 } }
            }
            field("Test" to "j") {
                resolver { fn { _, _, _, _, _ -> 2 } }
            }
        }

        val fields = module.fieldResolverExecutors.toList()
        assertEquals(2, fields.size)
        assertEquals(Coordinate("Test", "i"), fields[0].first)
        assertEquals(Coordinate("Test", "j"), fields[1].first)
    }

    @Test
    fun `DSL builds EngineTestModule with node resolvers`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            type("Test") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(objectType, mapOf("id" to id))
                }
            }
        }

        val nodes = module.nodeResolverExecutors.toList()
        assertEquals(1, nodes.size)
        assertEquals("Test", nodes[0].first)
    }

    @Test
    fun `DSL builds EngineTestModule with checkers`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            field("Test" to "i") {
                resolver { fn { _, _, _, _, _ -> 1 } }
                checker { fn { _, _ -> } }
            }
        }

        assertEquals(1, module.checkerExecutors.size)
        assertNotNull(module.checkerExecutors[Coordinate("Test", "i")])
    }

    @Test
    fun `DSL builds EngineTestModule with type checkers`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            type("Test") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(objectType, mapOf("id" to id))
                }
                checker { fn { _, _ -> } }
            }
        }

        assertEquals(1, module.typeCheckerExecutors.size)
        assertNotNull(module.typeCheckerExecutors["Test"])
    }

    @Test
    fun `buildExecutionRegistryConfigFile preserves field executor metadata`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            field("Test" to "i") {
                resolver {
                    resolverName("my-resolver")
                    fn { _, _, _, _, _ -> 42 }
                }
            }
        }

        val config = module.buildExecutionRegistryConfigFile()
        assertEquals(1, config.fields.size)
        val entry = config.fields[0]
        assertEquals("Test", entry.typeName)
        assertEquals("i", entry.fieldName)
        assertEquals("my-resolver", entry.attribution)
        assertEquals(false, entry.isBatching)
        assertNull(entry.objectSelections)
        assertNull(entry.querySelections)
        assertEquals(emptyMap<String, Any?>(), entry.tenantAPIData)
    }

    @Test
    fun `buildExecutionRegistryConfigFile preserves node executor metadata`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            type("Test") {
                nodeBatchedExecutor { selectors, _ ->
                    selectors.associateWith { Result.success(createEngineObjectData(objectType, mapOf("id" to it.id))) }
                }
            }
        }

        val config = module.buildExecutionRegistryConfigFile()
        assertEquals(1, config.nodes.size)
        val entry = config.nodes[0]
        assertEquals("Test", entry.typeName)
        assertEquals(true, entry.isBatching)
        assertEquals(emptyMap<String, Any?>(), entry.tenantAPIData)
    }

    @Test
    fun `MockExecutorFactory gets stored executors from its injected registry`() {
        val module = EngineTestModule(SCHEMA_SDL) {
            field("Test" to "i") {
                resolver { fn { _, _, _, _, _ -> 99 } }
            }
            type("Test") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(objectType, mapOf("id" to id))
                }
            }
        }

        val config = module.buildExecutionRegistryConfigFile()
        val codeInjector = runBlocking {
            MockExecutorCodeInjector(module.mockExecutorRegistry).bootstrap(config.tenantName.orEmpty(), null)
        }
        val factory = MockExecutorFactory(codeInjector, config)

        val fieldExecutor = factory.createFieldResolverExecutor(config.fields[0], module.fullSchema)
        val originalFieldExecutor = module.fieldResolverExecutors.first().second
        assertEquals(originalFieldExecutor, fieldExecutor)

        val nodeExecutor = factory.createNodeResolverExecutor(config.nodes[0], module.fullSchema)
        val originalNodeExecutor = module.nodeResolverExecutors.first().second
        assertEquals(originalNodeExecutor, nodeExecutor)
    }

    @Test
    fun `off-schema field resolver fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule(SCHEMA_SDL) {
                field("NonExistentType" to "field") {
                    resolver { fn { _, _, _, _, _ -> null } }
                }
            }.buildExecutionRegistryConfigFile()
        }
    }

    @Test
    fun `off-schema node resolver fails fast`() {
        val schema = """
            extend type Query { x: Int }
            type Foo { name: String }
        """
        assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule(schema) {
                type("Foo") {
                    nodeUnbatchedExecutor { _, _, _ ->
                        createEngineObjectData(objectType, emptyMap())
                    }
                }
            }.buildExecutionRegistryConfigFile()
        }
    }

    @Test
    fun `runFeatureTest smoke test`() {
        EngineTestModule(
            """
            extend type Query {
                hello: String
            }
            """
        ) {
            fieldWithValue("Query" to "hello", "world")
        }.runFeatureTest {
            runQuery("{ hello }").assertJson("""{"data": {"hello": "world"}}""")
        }
    }

    @Test
    fun `runFeatureTest with scoped schema`() {
        val fullSdl = """
            extend type Query {
                hello: String
                other: String
            }
        """
        val module = EngineTestModule(fullSdl) {
            fieldWithValue("Query" to "hello", "world")
            fieldWithValue("Query" to "other", "value")
        }
        val scopedSchema = createSchemaWithWiring(
            """
            extend type Query {
                hello: String
            }
            """
        )
        module.runFeatureTest(schema = scopedSchema) {
            runQuery("{ hello }").assertJson("""{"data": {"hello": "world"}}""")
        }
    }

    @Test
    fun `runFeatureTest with engineConfig`() {
        EngineTestModule(
            """
            extend type Query {
                hello: String
            }
            """
        ) {
            fieldWithValue("Query" to "hello", "world")
        }.runFeatureTest(engineConfig = EngineConfiguration.featureTestDefault) {
            runQuery("{ hello }").assertJson("""{"data": {"hello": "world"}}""")
        }
    }

    @Test
    fun `runFeatureTest withoutDefaultQueryNodeResolvers`() {
        EngineTestModule(
            """
            extend type Query {
                hello: String
            }
            """
        ) {
            fieldWithValue("Query" to "hello", "world")
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) {
            runQuery("{ hello }").assertJson("""{"data": {"hello": "world"}}""")
        }
    }

    @Test
    fun `runFeatureTest preserves resolver metadata identity`() {
        EngineTestModule(
            """
            extend type Query {
                value: Int
            }
            """
        ) {
            field("Query" to "value") {
                resolver {
                    resolverName("instrumented-resolver")
                    fn { _, _, _, _, _ -> 42 }
                }
            }
        }.runFeatureTest {
            runQuery("{ value }").assertJson("""{"data": {"value": 42}}""")
        }
    }

    @Test
    fun `runFeatureTest with node resolver`() {
        val globalId = java.util.Base64.getEncoder().encodeToString("Foo:123".toByteArray())
        EngineTestModule(
            """
            type Foo implements Node {
                id: ID!
                name: String
            }
            """
        ) {
            type("Foo") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(objectType, mapOf("id" to id, "name" to "foo-$id"))
                }
            }
        }.runFeatureTest {
            runQuery("""{ node(id: "$globalId") { ... on Foo { id name } } }""")
                .assertJson("""{"data": {"node": {"id": "$globalId", "name": "foo-$globalId"}}}""")
        }
    }

    @Test
    fun `fullSchema is accessible before runFeatureTest`() {
        val module = EngineTestModule(
            """
            extend type Query {
                hello: String
            }
            """
        ) {
            fieldWithValue("Query" to "hello", "world")
        }
        assertNotNull(module.fullSchema)
        assertNotNull(module.fullSchema.schema.getObjectType("Query"))
        module.runFeatureTest {
            runQuery("{ hello }").assertJson("""{"data": {"hello": "world"}}""")
        }
    }

    @Test
    fun `invoke with ViaductSchema`() {
        val schema = createSchemaWithWiring(
            """
            extend type Query {
                x: Int
            }
            """
        )
        val module = EngineTestModule(schema) {
            fieldWithValue("Query" to "x", 7)
        }
        module.runFeatureTest {
            runQuery("{ x }").assertJson("""{"data": {"x": 7}}""")
        }
    }
}
