package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

/**
 * Tests for FieldResolver instrumentation behavior.
 *
 * These tests verify that instrumentation callbacks (like onCompleted) are called
 * correctly under various field resolution scenarios.
 */
@ExperimentalCoroutinesApi
class FieldResolverInstrumentationTest {
    @Nested
    inner class FieldFetchingInstrumentationTest {
        private val userSdl = """
            type Query { user: User }
            type User { id: String, name: String }
        """.trimIndent()

        private val userResolvers = mapOf(
            "Query" to mapOf("user" to DataFetcher { mapOf("id" to "123", "name" to "Test User") }),
            "User" to mapOf(
                "id" to DataFetcher { (it.getSource() as Map<*, *>)["id"] },
                "name" to DataFetcher { (it.getSource() as Map<*, *>)["name"] }
            )
        )

        private fun fieldInstrumentation(
            targetPath: String,
            onCompleted: (Any?, Throwable?) -> Unit
        ) = object : ViaductModernInstrumentation.WithBeginFieldFetching {
            override fun beginFieldFetching(
                parameters: InstrumentationFieldFetchParameters,
                state: InstrumentationState?
            ): FieldFetchingInstrumentationContext? {
                if (parameters.executionStepInfo.path.toString() != targetPath) return null
                return object : FieldFetchingInstrumentationContext {
                    override fun onDispatched() {}

                    override fun onCompleted(
                        result: Any?,
                        t: Throwable?
                    ) = onCompleted(result, t)
                }
            }
        }

        private fun checkerDispatcher(block: suspend () -> CheckerResult): CheckerDispatcher {
            val dispatcher = object : CheckerDispatcher {
                override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()
                override lateinit var executor: CheckerExecutor

                override suspend fun execute(
                    arguments: Map<String, Any?>,
                    objectDataMap: Map<String, EngineObjectData>,
                    context: EngineExecutionContext,
                    checkerType: CheckerExecutor.CheckerType
                ) = block()
            }
            dispatcher.executor = object : CheckerExecutor {
                override suspend fun execute(
                    arguments: Map<String, Any?>,
                    objectDataMap: Map<String, EngineObjectData>,
                    context: EngineExecutionContext,
                    checkerType: CheckerExecutor.CheckerType
                ) = block()

                override val checkerMetadata = null
                override val requiredSelectionSets = dispatcher.requiredSelectionSets
            }
            return dispatcher
        }

        private fun failingChecker(message: String) =
            checkerDispatcher {
                object : CheckerResult.Error {
                    override val error = IllegalAccessException(message)

                    override fun isErrorForResolver(ctx: CheckerResultContext) = true

                    override fun combine(fieldResult: CheckerResult.Error) = this
                }
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted is called exactly once when data fetcher fails")
        fun onCompletedCalledOnceWhenDataFetcherFailed() =
            runExecutionTest {
                val onCompletedCallCount = AtomicInteger(0)

                val executionResult = executeViaductModernGraphQL(
                    sdl = "type Query { failingField: String }",
                    resolvers = mapOf("Query" to mapOf("failingField" to DataFetcher { throw RuntimeException("Data fetcher failed") })),
                    query = "{ failingField }",
                    instrumentations = listOf(fieldInstrumentation("/failingField") { _, _ -> onCompletedCallCount.incrementAndGet() })
                )

                assertEquals(1, executionResult.errors.size) { "Expected exactly one error from data fetcher failure" }
                assertEquals(1, onCompletedCallCount.get()) {
                    "beginFieldFetching onCompleted should be called exactly once, but was called ${onCompletedCallCount.get()} times"
                }
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted is called after type checker completes")
        fun onCompletedCalledAfterTypeCheckerComplete() =
            runExecutionTest {
                val typeCheckerDone = CountDownLatch(1)
                val instrumentationBegun = CountDownLatch(1)
                val onCompletedCalled = CountDownLatch(1)
                val onCompletedAfterTypeChecker = AtomicBoolean(false)

                val delayingTypeChecker = checkerDispatcher {
                    delay(100)
                    typeCheckerDone.countDown()
                    CheckerResult.Success
                }

                val instrumentation = object : ViaductModernInstrumentation.WithBeginFieldFetching {
                    override fun beginFieldFetching(
                        parameters: InstrumentationFieldFetchParameters,
                        state: InstrumentationState?
                    ): FieldFetchingInstrumentationContext? {
                        if (parameters.executionStepInfo.path.toString() != "/user") return null
                        instrumentationBegun.countDown()
                        return object : FieldFetchingInstrumentationContext {
                            override fun onDispatched() {}

                            override fun onCompleted(
                                result: Any?,
                                t: Throwable?
                            ) {
                                onCompletedAfterTypeChecker.set(typeCheckerDone.await(0, TimeUnit.MILLISECONDS))
                                onCompletedCalled.countDown()
                            }
                        }
                    }
                }

                val executionResult = executeViaductModernGraphQL(
                    sdl = userSdl,
                    resolvers = userResolvers,
                    query = "{ user { id name } }",
                    typeCheckerDispatchers = mapOf("User" to delayingTypeChecker),
                    instrumentations = listOf(instrumentation)
                )

                assertEquals(0, executionResult.errors.size) { "Expected no errors, but got: ${executionResult.errors}" }
                assertEquals(mapOf("user" to mapOf("id" to "123", "name" to "Test User")), executionResult.getData<Map<String, Any?>>())
                assertTrue(instrumentationBegun.await(1, TimeUnit.SECONDS)) { "beginFieldFetching instrumentation was never called." }
                assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFieldFetching onCompleted callback was never invoked." }
                assertTrue(onCompletedAfterTypeChecker.get()) { "beginFieldFetching onCompleted was called before type checker completed." }
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted is called exactly once when data fetcher succeeds with field checker")
        fun onCompletedCalledExactlyOnceWhenFieldCheckerFailed() =
            runExecutionTest {
                val onCompletedCallCount = AtomicInteger(0)

                val successFieldChecker = checkerDispatcher {
                    delay(50)
                    CheckerResult.Success
                }

                val executionResult = executeViaductModernGraphQL(
                    sdl = "type Query { checkedField: String }",
                    resolvers = mapOf("Query" to mapOf("checkedField" to DataFetcher { "success" })),
                    query = "{ checkedField }",
                    fieldCheckerDispatchers = mapOf(("Query" to "checkedField") to successFieldChecker),
                    instrumentations = listOf(fieldInstrumentation("/checkedField") { _, _ -> onCompletedCallCount.incrementAndGet() })
                )

                assertEquals(0, executionResult.errors.size) { "Expected no errors, but got: ${executionResult.errors}" }
                assertEquals(mapOf("checkedField" to "success"), executionResult.getData<Map<String, Any?>>())
                assertEquals(1, onCompletedCallCount.get()) {
                    "beginFieldFetching onCompleted should be called exactly once, but was called ${onCompletedCallCount.get()} times"
                }
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted receives field check error when field checker fails")
        fun onCompletedReceivesFieldCheckError() =
            runExecutionTest {
                var capturedError: Throwable? = null
                val onCompletedCalled = CountDownLatch(1)

                executeViaductModernGraphQL(
                    sdl = "type Query { checkedField: String }",
                    resolvers = mapOf("Query" to mapOf("checkedField" to DataFetcher { "success" })),
                    query = "{ checkedField }",
                    fieldCheckerDispatchers = mapOf(("Query" to "checkedField") to failingChecker("field check denied")),
                    instrumentations = listOf(
                        fieldInstrumentation("/checkedField") { _, t ->
                            capturedError = t
                            onCompletedCalled.countDown()
                        }
                    )
                )

                assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFieldFetching onCompleted callback was never invoked." }
                assertTrue(capturedError is IllegalAccessException) { "Expected IllegalAccessException but got ${capturedError?.javaClass}" }
                assertEquals("field check denied", capturedError?.message)
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted receives type check error when type checker fails")
        fun onCompletedReceivesTypeCheckError() =
            runExecutionTest {
                var capturedError: Throwable? = null
                val onCompletedCalled = CountDownLatch(1)

                executeViaductModernGraphQL(
                    sdl = userSdl,
                    resolvers = userResolvers,
                    query = "{ user { id name } }",
                    typeCheckerDispatchers = mapOf("User" to failingChecker("type check denied")),
                    instrumentations = listOf(
                        fieldInstrumentation("/user") { _, t ->
                            capturedError = t
                            onCompletedCalled.countDown()
                        }
                    )
                )

                assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFieldFetching onCompleted callback was never invoked." }
                assertTrue(capturedError is IllegalAccessException) { "Expected IllegalAccessException but got ${capturedError?.javaClass}" }
                assertEquals("type check denied", capturedError?.message)
            }

        @Test
        @DisplayName("beginFieldFetching onCompleted receives data fetcher error when both data fetcher and field checker fail in parallel mode")
        fun onCompletedReceivesDataFetcherErrorWhenBothFail() =
            runExecutionTest {
                var capturedError: Throwable? = null
                val onCompletedCalled = CountDownLatch(1)

                executeViaductModernGraphQL(
                    sdl = "type Query { checkedField: String }",
                    resolvers = mapOf("Query" to mapOf("checkedField" to DataFetcher { throw RuntimeException("data fetcher failed") })),
                    query = "{ checkedField }",
                    fieldCheckerDispatchers = mapOf(("Query" to "checkedField") to failingChecker("field check denied")),
                    instrumentations = listOf(
                        fieldInstrumentation("/checkedField") { _, t ->
                            capturedError = t
                            onCompletedCalled.countDown()
                        }
                    )
                )

                assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) { "beginFieldFetching onCompleted callback was never invoked." }
                assertTrue(capturedError is RuntimeException) { "Expected RuntimeException from data fetcher but got ${capturedError?.javaClass}" }
                assertEquals("data fetcher failed", capturedError?.message) {
                    "Data fetcher error should take priority over field checker error"
                }
            }
    }
}
