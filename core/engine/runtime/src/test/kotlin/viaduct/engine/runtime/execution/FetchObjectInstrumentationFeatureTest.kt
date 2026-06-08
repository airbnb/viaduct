package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import java.lang.Thread.sleep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest

/**
 * Tests for [IViaductInstrumentation.WithBeginFetchObject] ordering guarantees and
 * mutation field checker failure handling.
 */
@ExperimentalCoroutinesApi
class FetchObjectInstrumentationFeatureTest {
    @Test
    @DisplayName("beginFetchObject onCompleted is called after all parallel field resolvers finish")
    fun fetchObjectOnCompletedAfterParallelFieldsFetched() {
        val resolversDone = CountDownLatch(2)
        val instrumentationBegun = CountDownLatch(1)
        val onCompletedCalled = CountDownLatch(1)
        var onCompletedAfterAllFields = false

        MockLegacyTenantModuleBootstrapper("extend type Query { string1: String, string2: String }") {
            field("Query" to "string1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        sleep(300)
                        resolversDone.countDown()
                        "1"
                    }
                }
            }
            field("Query" to "string2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        sleep(300)
                        resolversDone.countDown()
                        "2"
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = makeBeginFetchObjectInstrumentation(instrumentationBegun, resolversDone, onCompletedCalled) { onCompletedAfterAllFields = it },
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("{string1, string2}").assertJson("""{"data": {"string1": "1", "string2": "2"}}""")

            assertTrue(instrumentationBegun.await(1, TimeUnit.SECONDS)) { "beginFetchObject never called" }
            assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFetchObject onCompleted never invoked" }
            assertTrue(onCompletedAfterAllFields) { "beginFetchObject onCompleted fired before all field resolvers finished" }
        }
    }

    @Test
    @DisplayName("beginFetchObject onCompleted is called after all serial mutation field resolvers finish")
    fun fetchObjectOnCompletedAfterSerialMutationFieldsFetched() {
        val resolverBegun = AtomicInteger(0)
        val resolversDone = CountDownLatch(2)
        val instrumentationBegun = CountDownLatch(1)
        val onCompletedCalled = CountDownLatch(1)
        var onCompletedAfterAllFields = false

        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { initialString: String }
            extend type Mutation { string1: String, string2: String }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "initialString", "InitialValue")
            field("Mutation" to "string1") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverBegun.incrementAndGet()
                        sleep(300)
                        resolversDone.countDown()
                        resolverBegun.get().toString()
                    }
                }
            }
            field("Mutation" to "string2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverBegun.incrementAndGet()
                        sleep(300)
                        resolversDone.countDown()
                        resolverBegun.get().toString()
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = makeBeginFetchObjectInstrumentation(instrumentationBegun, resolversDone, onCompletedCalled) { onCompletedAfterAllFields = it },
                chainInstrumentationWithDefaults = true,
            )
        ) {
            runQuery("mutation { string1, string2 }").assertJson("""{"data": {"string1": "1", "string2": "2"}}""")

            assertTrue(instrumentationBegun.await(1, TimeUnit.SECONDS)) { "beginFetchObject never called" }
            assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFetchObject onCompleted never invoked" }
            assertTrue(onCompletedAfterAllFields) { "beginFetchObject onCompleted fired before all mutation field resolvers finished" }
        }
    }

    private fun makeBeginFetchObjectInstrumentation(
        instrumentationBegun: CountDownLatch,
        resolversDone: CountDownLatch,
        onCompletedCalled: CountDownLatch,
        setOnCompletedAfterAllFields: (Boolean) -> Unit
    ) = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFetchObject {
        override fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit> {
            instrumentationBegun.countDown()
            return SimpleInstrumentationContext.whenCompleted { _, _ ->
                setOnCompletedAfterAllFields(resolversDone.await(0, TimeUnit.MILLISECONDS))
                onCompletedCalled.countDown()
            }
        }
    }.asStandardInstrumentation

    @Test
    @DisplayName("mutation returning list with failed field checker handles result type correctly")
    fun mutationReturningListWithFailedFieldCheckerHandlesResultType() {
        MockLegacyTenantModuleBootstrapper(
            """
            extend type Query { empty: Int }
            extend type Mutation { getUrls: [String!] }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "empty", null)
            field("Mutation" to "getUrls") {
                resolver {
                    fn { _, _, _, _, _ -> listOf("url1", "url2", "url3") }
                }
                checker {
                    fn { _, _ ->
                        throw IllegalAccessException("Privacy check failed: user not authorized")
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("mutation { getUrls }")
            assertEquals(1, result.errors.size) { "Expected exactly one error from failed field check" }
            assertTrue(
                result.errors[0].message.contains("Privacy check failed") ||
                    result.errors[0].message.contains("not authorized"),
                "Expected error message to contain privacy failure info"
            )
            assertEquals(mapOf("getUrls" to null), result.getData<Map<String, Any?>>())
        }
    }
}
