package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest

class ResolverInstrumentationFeatureTest {
    @Test
    fun `field resolver invokes instrumentation`() {
        val resolverToFields = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = fieldTrackingInstrumentation(resolverToFields)

        EngineTestModule("extend type Query { idField: String, string1: String }") {
            field("Query" to "idField") {
                resolver {
                    resolverName("query-id-field-resolver")
                    fn { _, _, _, _, _ -> "id-value" }
                }
            }
            field("Query" to "string1") {
                resolver {
                    resolverName("query-string1-resolver")
                    objectSelections("idField")
                    fn { _, obj, _, _, _ -> obj.fetchAs<String>("idField") }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(resolverInstrumentation = instrumentation)
        ) {
            runQuery("query testQuery {string1}").assertJson("{data: {string1: \"id-value\"}}")

            assertEquals(setOf("query-id-field-resolver", "query-string1-resolver"), resolverToFields.keys)
            assertEquals(listOf("idField"), resolverToFields["query-string1-resolver"]?.toList())
        }
    }

    @Test
    fun `field checker invokes instrumentation`() {
        val checkerToFields = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = checkerTrackingInstrumentation(checkerToFields)

        EngineTestModule("extend type Query { string1: String, string2: String }") {
            field("Query" to "string1") {
                resolver { fn { _, _, _, _, _ -> "string1" } }
                checker {
                    objectSelections("key", "string2")
                    fn { _, objectDataMap ->
                        objectDataMap["key"]?.fetch("string2")
                        viaduct.engine.api.CheckerResult.Success
                    }
                }
            }
            fieldWithValue("Query" to "string2", "string2")
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(resolverInstrumentation = instrumentation)
        ) {
            runQuery("query { string1 }").assertJson("{data: {string1: \"string1\"}}")

            assertTrue("checker" in checkerToFields.keys, "checker RSS materialization should invoke resolver instrumentation")
            assertTrue("string2" in checkerToFields["checker"]!!.toSet())
        }
    }

    @Test
    fun `field checker variable-resolver RSS materialization invokes instrumentation`() {
        val checkerToFields = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = checkerTrackingInstrumentation(checkerToFields)

        // The checker's required selection set uses a variable ($gate) that is sourced from a
        // variable resolver. The variable resolver has its OWN required selection set ("gateField")
        // that must be materialized before the checker's RSS can be resolved. That variable-RSS
        // materialization flows through FieldExecutionHelpers.resolveVariables, which now threads
        // the in-scope ResolverInstrumentationContext. We assert that the field read during the
        // variable-RSS materialization (gateField) fires fetch-selection instrumentation.
        EngineTestModule("extend type Query { string1: String, string2: String, gateField: Boolean }") {
            field("Query" to "string1") {
                resolver { fn { _, _, _, _, _ -> "string1" } }
                checker {
                    objectSelections("key", "string2 @include(if: ${'$'}gate)") {
                        variables(
                            "gate",
                            rss = createRSS("Query", "gateField")
                        ) { resolveCtx, _ ->
                            mapOf("gate" to resolveCtx.objectData.fetchAs<Boolean>("gateField"))
                        }
                    }
                    fn { _, _ ->
                        viaduct.engine.api.CheckerResult.Success
                    }
                }
            }
            fieldWithValue("Query" to "string2", "string2")
            fieldWithValue("Query" to "gateField", true)
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(resolverInstrumentation = instrumentation)
        ) {
            runQuery("query { string1 }").assertJson("{data: {string1: \"string1\"}}")

            assertTrue(
                "checker" in checkerToFields.keys,
                "checker RSS materialization should invoke resolver instrumentation"
            )
            assertTrue(
                "gateField" in checkerToFields["checker"]!!.toSet(),
                "fields read during checker variable-resolver RSS materialization should fire fetch-selection instrumentation"
            )
        }
    }

    @Test
    fun `ctx query subquery materialization invokes fetch selection instrumentation`() {
        val resolverToFields = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = fieldTrackingInstrumentation(resolverToFields)

        EngineTestModule(
            """
            extend type Query {
                rootValue: Int
                container: Container
            }

            type Container {
                derivedFromQuery: Int
            }
            """.trimIndent()
        ) {
            field("Query" to "rootValue") {
                resolver {
                    resolverName("query-root-value-resolver")
                    fn { _, _, _, _, _ -> 42 }
                }
            }

            field("Query" to "container") {
                resolver {
                    resolverName("query-container-resolver")
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "derivedFromQuery") {
                resolver {
                    resolverName("container-derived-resolver")
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Query", "rootValue", emptyMap())
                        val queryResult = ctx.query(selectionSet = rss)
                        queryResult.fetchAs<Int>("rootValue") * 2
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(resolverInstrumentation = instrumentation)
        ) {
            runQuery("{ container { derivedFromQuery } }")
                .assertJson("""{"data": {"container": {"derivedFromQuery": 84}}}""")

            // The resolver that calls ctx.query() materializes the subquery's selected field,
            // so beginFetchSelection must fire for "rootValue" keyed under that resolver's name.
            assertTrue(
                "container-derived-resolver" in resolverToFields.keys,
                "ctx.query() resolver should be tracked by fetch-selection instrumentation"
            )
            assertTrue(
                "rootValue" in resolverToFields["container-derived-resolver"]!!.toSet(),
                "ctx.query() subquery materialization should fire beginFetchSelection for the selected field"
            )
        }
    }

    @Test
    fun `ctx mutation subquery materialization invokes fetch selection instrumentation`() {
        val resolverToFields = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        val instrumentation = fieldTrackingInstrumentation(resolverToFields)

        EngineTestModule(
            """
            extend type Query {
                container: Container
            }

            extend type Mutation {
                incrementCounter: Int
            }

            type Container {
                triggerMutation: Int
            }
            """.trimIndent()
        ) {
            var counter = 0

            field("Mutation" to "incrementCounter") {
                resolver {
                    resolverName("mutation-increment-resolver")
                    fn { _, _, _, _, _ -> ++counter }
                }
            }

            field("Query" to "container") {
                resolver {
                    resolverName("query-container-resolver")
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Container"),
                            mapOf()
                        )
                    }
                }
            }

            field("Container" to "triggerMutation") {
                resolver {
                    resolverName("container-trigger-mutation-resolver")
                    fn { _, _, _, _, ctx ->
                        val rss = ctx.engineSelectionSetFactory
                            .engineSelectionSet("Mutation", "incrementCounter", emptyMap())
                        val mutationResult = ctx.mutation(selectionSet = rss)
                        mutationResult.fetchAs<Int>("incrementCounter")
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(resolverInstrumentation = instrumentation)
        ) {
            runQuery("{ container { triggerMutation } }")
                .assertJson("""{"data": {"container": {"triggerMutation": 1}}}""")

            // The resolver that calls ctx.mutation() materializes the subquery's selected field,
            // so beginFetchSelection must fire for "incrementCounter" keyed under that resolver's name.
            assertTrue(
                "container-trigger-mutation-resolver" in resolverToFields.keys,
                "ctx.mutation() resolver should be tracked by fetch-selection instrumentation"
            )
            assertTrue(
                "incrementCounter" in resolverToFields["container-trigger-mutation-resolver"]!!.toSet(),
                "ctx.mutation() subquery materialization should fire beginFetchSelection for the selected field"
            )
        }
    }

    private data class TrackingState(var key: String? = null) : ViaductResolverInstrumentation.InstrumentationState

    private fun fieldTrackingInstrumentation(resolverToFields: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>): ViaductResolverInstrumentation =
        object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState =
                TrackingState()

            override fun <T> instrumentResolverExecution(
                resolver: ResolverFunction<T>,
                parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): ResolverFunction<T> {
                val name = parameters.resolverMetadata.name
                (state as TrackingState).key = name
                resolverToFields.computeIfAbsent(name) { CopyOnWriteArrayList() }
                return resolver
            }

            override fun beginFetchSelection(
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                val resolverName = (state as TrackingState).key ?: return ViaductResolverInstrumentation.FetchSelectionInstrumentation.NOOP
                resolverToFields[resolverName]?.add(parameters.selection)
                return ViaductResolverInstrumentation.FetchSelectionInstrumentation.NOOP
            }
        }

    private fun checkerTrackingInstrumentation(checkerToFields: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>): ViaductResolverInstrumentation =
        object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState =
                TrackingState("checker")

            override fun beginFetchSelection(
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                val key = (state as TrackingState).key ?: return ViaductResolverInstrumentation.FetchSelectionInstrumentation.NOOP
                checkerToFields.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(parameters.selection)
                return ViaductResolverInstrumentation.FetchSelectionInstrumentation.NOOP
            }
        }
}
