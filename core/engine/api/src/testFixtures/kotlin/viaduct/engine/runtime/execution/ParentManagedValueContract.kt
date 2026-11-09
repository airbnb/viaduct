package viaduct.engine.runtime.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
interface ParentManagedValueContract : QueryExecutionContract {
    companion object {
        private val SDL = """
            extend type Query {
                foo: Foo
                nested: Level1
                list: [Item]
            }

            type Foo {
                bar: String
            }

            type Level1 {
                level2: Level2
                value: String
            }

            type Level2 {
                level3: Level3
                value: String
            }

            type Level3 {
                value: String
            }

            type Item {
                name: String
            }
        """.trimIndent()
    }

    @Test
    fun `resolver takes over nested selection`() {
        EngineTestModule(SDL) {
            field("Query" to "foo") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ParentManagedValue(mapOf("bar" to "parent-value"))
                    }
                }
            }
            fieldWithValue("Foo" to "bar", "child-resolver-value")
        }.runContractTest {
            runQuery("{ foo { bar } }")
                .assertJson("{data: {foo: {bar: \"parent-value\"}}}")
        }
    }

    @Test
    fun `policy propagates through deep nesting`() {
        EngineTestModule(SDL) {
            field("Query" to "nested") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ParentManagedValue(
                            mapOf(
                                "value" to "l1",
                                "level2" to mapOf(
                                    "value" to "l2",
                                    "level3" to mapOf(
                                        "value" to "l3"
                                    )
                                )
                            )
                        )
                    }
                }
            }
            // Register resolvers that should be skipped
            field("Level1" to "level2") { resolver { fn { _, _, _, _, _ -> throw IllegalStateException("Should be skipped") } } }
            field("Level2" to "level3") { resolver { fn { _, _, _, _, _ -> throw IllegalStateException("Should be skipped") } } }
            field("Level3" to "value") { resolver { fn { _, _, _, _, _ -> throw IllegalStateException("Should be skipped") } } }
        }.runContractTest {
            runQuery(
                """
                {
                    nested {
                        value
                        level2 {
                            value
                            level3 {
                                value
                            }
                        }
                    }
                }
                """.trimIndent()
            ).assertJson(
                """
                {
                    data: {
                        nested: {
                            value: "l1",
                            level2: {
                                value: "l2",
                                level3: {
                                    value: "l3"
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `policy propagates to list items`() {
        EngineTestModule(SDL) {
            field("Query" to "list") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ParentManagedValue(
                            listOf(
                                mapOf("name" to "item1"),
                                mapOf("name" to "item2")
                            )
                        )
                    }
                }
            }
            // Register resolver that should be skipped
            field("Item" to "name") {
                resolver {
                    fn { _, _, _, _, _ -> throw IllegalStateException("Should be skipped") }
                }
            }
        }.runContractTest {
            runQuery("{ list { name } }")
                .assertJson(
                    """
                    {
                        data: {
                            list: [
                                { name: "item1" },
                                { name: "item2" }
                            ]
                        }
                    }
                    """.trimIndent()
                )
        }
    }
}
