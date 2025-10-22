package viaduct.engine.runtime.fixtures

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid

/**
 * Example test demonstrating the EngineFeatureTest framework usage.
 */
class EngineFeatureTestExample {
    @Test
    fun `simple resolver test`() {
        val schemaSDL = """
            extend type Query {
                hello: String
                number: Int
                withArgs(name: String!): String
            }
        """.trimIndent()

        MockTenantModuleBootstrapper(schemaSDL) {
            fieldWithValue("Query" to "hello", "world")
            fieldWithValue("Query" to "number", 42)
            field("Query" to "withArgs") {
                resolver {
                    fn { args, _, _, _, _ ->
                        "Hello, ${args["name"]}!"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("""{ hello number withArgs(name: "Alice") }""")
                .assertJson("""{"data": {"hello": "world", "number": 42, "withArgs": "Hello, Alice!"}}""")
        }
    }

    @Test
    fun `simple query selections test`() {
        MockTenantModuleBootstrapper(
            """
            extend type Query {
                one: Int
                twoContainer: TwoContainer
            }

            type TwoContainer {
                two: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "one", 1)
            fieldWithValue("Query" to "twoContainer", mkEngineObjectData(queryType, mapOf()))
            field("TwoContainer" to "two") {
                resolver {
                    querySelections("one")
                    fn { _, _, qry, _, _ -> qry.fetchAs<Int>("one") + 1 }
                }
            }
        }.runFeatureTest {
            runQuery("""{ twoContainer { two } }""")
                .assertJson("""{"data": {"twoContainer": {"two": 2}}}""")
        }
    }

    @Disabled("Test loops due to bug in engine - exposes issue with fieldWithValue + checker on same field")
    @Test
    fun `resolver with checker test`() {
        var checkerExecuted = false

        val schemaSDL = """
            extend type Query {
                secureField: String
            }
        """.trimIndent()

        MockTenantModuleBootstrapper(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    objectSelections("key", "fragment _ on Query { secureField }")
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ secureField }")
                .assertJson("""{"data": {"secureField": "secure data"}}""")
        }
        assertTrue(checkerExecuted)
    }

    @Test
    fun `resolver with checker test copy`() {
        var checkerExecuted = false

        val schemaSDL = """
            extend type Query {
                secureField: String
            }
        """.trimIndent()

        MockTenantModuleBootstrapper(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ secureField }")
                .assertJson("""{"data": {"secureField": "secure data"}}""")
        }
        assertTrue(checkerExecuted)
    }

    @Test
    fun `node resolver test`() {
        val schemaSDL = """
            type TestNode implements Node {
                id: ID!
                name: String
            }
        """.trimIndent()

        // Here we can also do:
        // ```
        // val testSchema = mkSchemaWithWiring(schemaSDL)
        // val testNodeType = testSchema.schema.getObjectType("TestNode")
        // MockTenantModuleBootstrapper(testSchema) { ... }
        // ```
        // then `testSchema` and `testNodeType` can be reused in the test.
        MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("123", schema.schema.getObjectType("TestNode"))
                    }
                }
            }
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    mkEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Test Node $id")
                    )
                }
            }
        }.runFeatureTest(withoutDefaultQueryNodeResolvers = true) { // Disabling system level node resolvers for test
            runQuery("{ node(id: 123) { id ... on TestNode { name } } }")
                .assertJson("""{data: { node: { id: "123", name: "Test Node 123"} } }""")
        }
    }

    @Test
    fun `test from kdoc`() {
        MockTenantModuleBootstrapper(
            """
           extend type Query {
               hello: String
               world: String
               greeting: String
               calc(n: Int): Int
               answer: Int
           }"""
        ) {
            fieldWithValue("Query" to "hello", "Hello") // resolves to a constant value
            fieldWithValue("Query" to "world", "World") // resolves to a constant value

            field("Query" to "greeting") {
                // Resolver function for this field
                resolver {
                    objectSelections("hello world")
                    fn { _, obj, _, _, _ -> "${obj.fetch("hello")}, ${obj.fetch("world")}!" }
                }
            }

            field("Query" to "calc") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("n") + 1 }
                }
            }

            field("Query" to "answer") {
                // Resolver function for this field
                resolver {
                    objectSelections("calc(n:\$v)") {
                        variables("v") { _ -> mapOf("v" to 41) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetch("calc") }
                }

                // Checker function for this field (optional)
                checker {
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }.runFeatureTest {
            runQuery("{ answer greeting }")
                .assertJson("""{ data: { answer: 42, greeting: "Hello, World!" } }""")
        }
    }

    @Test
    fun `test invalid object fragment`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockTenantModuleBootstrapper(
                """
                extend type Query {
                    foo: Int
                }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver {
                        objectSelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }.runFeatureTest {
                runQuery("{ foo }")
            }
        }
    }

    @Test
    fun `test invalid query fragment`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockTenantModuleBootstrapper(
                """
                extend type Query {
                    foo: Int
                }
                """.trimIndent()
            ) {
                field("Query" to "foo") {
                    resolver {
                        querySelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }.runFeatureTest {
                runQuery("{ foo }")
                    .assertJson("""{ data: { answer: 42, greeting: "Hello, World!" } }""")
            }
        }
    }
}
