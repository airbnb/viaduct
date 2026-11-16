package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Test
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.graphql.test.assertJson

interface CompleteSelectionSetContract : QueryExecutionContract {
    // ==================== Object-typed completions (non-Query) ====================
    // These exercise child-plan parameter creation where isRootQueryQueryPlan=false,
    // which uses currentObjectEngineResult. This is the primary production path for
    // DFP shims (ClassicDerivedFieldResolverExecutor) and checker execution.

    @Test
    fun `object-typed completion with scalar fields`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            type Container {
                x: Int
                y: String
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "x", 42)
            fieldWithValue("Container" to "y", "hello")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    // Targets "Container" (not Query), so isRootQueryQueryPlan = false
                    objectSelections("x y")
                    fn { _, _, _, _, ctx ->
                        val rss = createRSS("Container", "x y")
                        val result = ctx.completeSelectionSet(rss)

                        val data = result.getData<Map<String, Any?>>()
                        "x=${data["x"]}, y=${data["y"]}"
                    }
                }
            }
        }.runContractTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "x=42, y=hello"}}}""")
        }
    }

    @Test
    fun `object-typed completion resolves required selection set variables`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            type Container {
                value: Int
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "value", 42)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    objectSelections("value")
                    fn { _, _, _, _, ctx ->
                        val rss = createRSS(
                            "Container",
                            "value @include(if: ${'$'}includeValue)",
                            listOf(VariablesResolver.const(mapOf("includeValue" to true)))
                        )
                        val result = ctx.completeSelectionSet(rss)

                        val data = result.getData<Map<String, Any?>>()
                        "value=${data["value"]}"
                    }
                }
            }
        }.runContractTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "value=42"}}}""")
        }
    }

    @Test
    fun `object-typed completion indexes variable RSS before resolving variables`() {
        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            type Container {
                value: Int
                gate: Boolean
                completedResult: Int
            }
            """.trimIndent()
        ) {
            fieldWithValue("Container" to "value", 42)
            fieldWithValue("Container" to "gate", true)

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            emptyMap()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    objectSelections("value gate")
                    fn { _, _, _, _, ctx ->
                        val variableRss = createRSS("Container", "gate")
                        val completionRss = createRSS(
                            "Container",
                            "value @include(if: ${'$'}includeValue)",
                            listOf(
                                MockVariablesResolver(
                                    "includeValue",
                                    requiredSelectionSet = variableRss,
                                ) { resolveCtx, _ ->
                                    mapOf("includeValue" to resolveCtx.objectData.get("gate"))
                                }
                            )
                        )
                        val result = ctx.completeSelectionSet(completionRss)

                        val data = result.getData<Map<String, Any?>>()
                        data["value"]
                    }
                }
            }
        }.runContractTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": 42}}}""")
        }
    }

    // ==================== Query-typed completions ====================
    // These exercise child-plan parameter creation where isRootQueryQueryPlan=true,
    // which uses queryEngineResult. Some DFPs have querySelections that follow this path.

    @Test
    fun `query-typed completion via querySelections`() {
        EngineTestModule(
            """
            extend type Query {
                rootValue: Int
                name: String
                container: Container
            }

            type Container {
                completedResult: String
            }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "rootValue", 42)
            fieldWithValue("Query" to "name", "Alice")

            field("Query" to "container") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "completedResult") {
                resolver {
                    // Targets "Query" type, so isRootQueryQueryPlan = true
                    querySelections("rootValue name")
                    fn { _, _, _, _, ctx ->
                        val requiredSS = createRSS("Query", "rootValue name")
                        val result = ctx.completeSelectionSet(requiredSS)

                        val data = result.getData<Map<String, Any?>>()
                        "rootValue=${data["rootValue"]}, name=${data["name"]}"
                    }
                }
            }
        }.runContractTest {
            runQuery("{ container { completedResult } }")
                .assertJson("""{"data": {"container": {"completedResult": "rootValue=42, name=Alice"}}}""")
        }
    }
}
