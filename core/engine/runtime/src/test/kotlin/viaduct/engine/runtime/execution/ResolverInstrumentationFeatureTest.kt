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
            field("Query" to "string2") {
                resolver { fn { _, _, _, _, _ -> "string2" } }
            }
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
            field("Query" to "string2") {
                resolver { fn { _, _, _, _, _ -> "string2" } }
            }
            field("Query" to "gateField") {
                resolver { fn { _, _, _, _, _ -> true } }
            }
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
