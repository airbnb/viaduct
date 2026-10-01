package viaduct.engine.runtime2.execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/fixtures/EngineFeatureTestExample.kt
// Copied 8 out of 8 tests as of 2026-08-21

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest

/**
 * Example test demonstrating the EngineFeatureTest framework usage.
 */
class EngineFeatureTestExample {
    @Test
    fun `simple resolver test`() {
        val schemaSDL =
            """
            extend type Query {
                hello: String
                number: Int
                withArgs(name: String!): String
            }
            """.trimIndent()

        EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "hello", "world")
            fieldWithValue("Query" to "number", 42)
            field("Query" to "withArgs") {
                resolver {
                    fn { args, _, _, _, _ ->
                        "Hello, ${args["name"]}!"
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("""{ hello number withArgs(name: "Alice") }""")
                .assertJson(
                    """{"data": {"hello": "world", "number": 42, "withArgs": "Hello, Alice!"}}""",
                )
        }
    }

    @Test
    fun `simple query selections test`() {
        EngineTestModule(
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
            fieldWithValue("Query" to "twoContainer", createEngineObjectData(queryType, mapOf()))
            field("TwoContainer" to "two") {
                resolver {
                    querySelections("one")
                    fn { _, _, qry, _, _ -> qry.fetchAs<Int>("one") + 1 }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("""{ twoContainer { two } }""")
                .assertJson("""{"data": {"twoContainer": {"two": 2}}}""")
        }
    }

    @Disabled("TODO: AccessChk")
    @Test
    fun `resolver with checker test`() {
        var checkerExecuted = false

        val schemaSDL = """
            extend type Query {
                secureField: String
            }
        """.trimIndent()

        EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    objectSelections("key", "fragment _ on Query { secureField }")
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ secureField }")
                .assertJson("""{"data": {"secureField": "secure data"}}""")
        }
        assertTrue(checkerExecuted)
    }

    @Disabled("TODO: AccessChk")
    @Test
    fun `resolver with checker test copy`() {
        var checkerExecuted = false

        val schemaSDL = """
            extend type Query {
                secureField: String
            }
        """.trimIndent()

        EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ secureField }")
                .assertJson("""{"data": {"secureField": "secure data"}}""")
        }
        assertTrue(checkerExecuted)
    }

    @Test
    fun `node resolver test`() {
        val schemaSDL =
            """
            type TestNode implements Node {
                id: ID!
                name: String
            }
            """.trimIndent()

        EngineTestModule(schemaSDL) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(
                            "123",
                            requireNotNull(schema.schema.getObjectType("TestNode")),
                        )
                    }
                }
            }
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Test Node $id"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ node(id: 123) { id ... on TestNode { name } } }")
                .assertJson("""{data: { node: { id: "123", name: "Test Node 123"} } }""")
        }
    }

    @Disabled("TODO: AccessChk")
    @Test
    fun `test from kdoc`() {
        EngineTestModule(
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
                        variables("v") { _, _ -> mapOf("v" to 41) }
                    }
                    fn { _, obj, _, _, _ -> obj.fetch("calc") }
                }

                // Checker function for this field (optional)
                checker {
                    fn { _, _ -> /* validation logic */ }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ answer greeting }")
                .assertJson("""{ data: { answer: 42, greeting: "Hello, World!" } }""")
        }
    }

    @Test
    fun `test invalid object fragment`() {
        val err = assertThrows<IllegalArgumentException> {
            EngineTestModule(
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
            }.runQPlanFeatureTest {
                runQuery("{ foo }")
            }
        }
        assertTrue(err.message.orEmpty().contains("Invalid GraphQL fragment"), err.message.orEmpty())
    }

    @Test
    fun `test invalid query fragment`() {
        val err = assertThrows<IllegalArgumentException> {
            EngineTestModule(
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
            }.runQPlanFeatureTest {
                runQuery("{ foo }")
                    .assertJson("""{ data: { answer: 42, greeting: "Hello, World!" } }""")
            }
        }
        assertTrue(err.message.orEmpty().contains("Invalid GraphQL fragment"), err.message.orEmpty())
    }
}
