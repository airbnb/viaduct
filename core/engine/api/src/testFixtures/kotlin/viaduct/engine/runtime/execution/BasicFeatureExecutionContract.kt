package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs

interface BasicFeatureExecutionContract : QueryExecutionContract {
    val resolverSchemaSDL get() =
        """
        extend type Query {
            hello: String
            number: Int
            withArgs(name: String!): String
        }
        """.trimIndent()

    val querySelectionSchemaSDL get() =
        """
        extend type Query {
            one: Int
            twoContainer: TwoContainer
        }

        type TwoContainer {
            two: Int
        }
        """.trimIndent()

    fun createResolverTestModule() =
        EngineTestModule(resolverSchemaSDL) {
            fieldWithValue("Query" to "hello", "world")
            fieldWithValue("Query" to "number", 42)
            field("Query" to "withArgs") {
                resolver { fn { args, _, _, _, _ -> "Hello, ${args["name"]}!" } }
            }
        }

    fun createQuerySelectionTestModule() =
        EngineTestModule(querySelectionSchemaSDL) {
            fieldWithValue("Query" to "one", 1)
            fieldWithValue("Query" to "twoContainer", createEngineObjectData(queryType, mapOf()))
            field("TwoContainer" to "two") {
                resolver {
                    querySelections("one")
                    fn { _, _, qry, _, _ -> qry.fetchAs<Int>("one") + 1 }
                }
            }
        }

    @Test
    fun `simple resolver test`() {
        assertQuery(
            createResolverTestModule(),
            """{ hello number withArgs(name: "Alice") }""",
            """{"data": {"hello": "world", "number": 42, "withArgs": "Hello, Alice!"}}""",
        )
    }

    @Test
    fun `simple query selections test`() {
        assertQuery(
            createQuerySelectionTestModule(),
            """{ twoContainer { two } }""",
            """{"data": {"twoContainer": {"two": 2}}}""",
        )
    }

    @Test
    fun `resolver with checker test`() {
        var checkerExecuted = false

        val schemaSDL = """
            extend type Query {
                secureField: String
            }
        """.trimIndent()

        val module = EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    objectSelections("key", "fragment _ on Query { secureField }")
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }
        assertQuery(module, "{ secureField }", """{"data": {"secureField": "secure data"}}""")
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

        val module = EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "secureField", "secure data")
            field("Query" to "secureField") {
                checker {
                    fn { _, _ ->
                        checkerExecuted = true
                    }
                }
            }
        }
        assertQuery(module, "{ secureField }", """{"data": {"secureField": "secure data"}}""")
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

        val module = EngineTestModule(schemaSDL) {
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
        }
        assertQuery(
            module,
            "{ node(id: 123) { id ... on TestNode { name } } }",
            """{data: { node: { id: "123", name: "Test Node 123"} } }""",
            withoutDefaultQueryNodeResolvers = true,
        )
    }

    @Test
    fun `test from kdoc`() {
        val module = EngineTestModule(
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
        }
        assertQuery(module, "{ answer greeting }", """{ data: { answer: 42, greeting: "Hello, World!" } }""")
    }

    @Test
    fun `test invalid object fragment`() {
        val error = assertThrows<Exception> {
            val module = EngineTestModule("extend type Query { foo: Int }") {
                field("Query" to "foo") {
                    resolver {
                        objectSelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }
            executeQuery(module, "{ foo }")
        }
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }

    @Test
    fun `test invalid query fragment`() {
        val error = assertThrows<Exception> {
            val module = EngineTestModule("extend type Query { foo: Int }") {
                field("Query" to "foo") {
                    resolver {
                        querySelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }
            executeQuery(module, "{ foo }")
        }
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }
}
