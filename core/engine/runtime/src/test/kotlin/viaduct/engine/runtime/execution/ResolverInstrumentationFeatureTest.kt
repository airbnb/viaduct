package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.EngineTestModule
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

            val checkerKey = checkerToFields.keys.find { it.contains("string1") }
            assertTrue(checkerKey != null, "checker instrumentation should be invoked for string1 checker")
            assertTrue("string2" in checkerToFields[checkerKey!!]!!.toSet())
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

            override fun shouldInstrumentFetchSelections(state: ViaductResolverInstrumentation.InstrumentationState?) = true

            override fun <T> instrumentFetchSelection(
                fetchFn: FetchFunction<T>,
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): FetchFunction<T> {
                val resolverName = (state as TrackingState).key ?: return fetchFn
                resolverToFields[resolverName]?.add(parameters.selection)
                return fetchFn
            }
        }

    private fun checkerTrackingInstrumentation(checkerToFields: ConcurrentHashMap<String, CopyOnWriteArrayList<String>>): ViaductResolverInstrumentation =
        object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState =
                TrackingState()

            override fun shouldInstrumentFetchSelections(state: ViaductResolverInstrumentation.InstrumentationState?) = true

            override fun <T> instrumentFetchSelection(
                fetchFn: FetchFunction<T>,
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): FetchFunction<T> {
                val key = (state as TrackingState).key ?: return fetchFn
                checkerToFields[key]?.add(parameters.selection)
                return fetchFn
            }

            override fun <T> instrumentAccessChecker(
                checker: CheckerFunction<T>,
                parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?
            ): CheckerFunction<T> {
                val key = parameters.checkerMetadata.toTagString()
                (state as TrackingState).key = key
                checkerToFields.computeIfAbsent(key) { CopyOnWriteArrayList() }
                return checker
            }
        }
}
