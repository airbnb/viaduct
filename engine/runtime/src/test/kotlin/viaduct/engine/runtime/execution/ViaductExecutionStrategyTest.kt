@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.DataFetcherResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.schema.DataFetcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.InternalDataLoader
import viaduct.dataloader.MappedBatchLoadFn
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.RequestScopeCancellationException
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeQuery
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.getLocalContextForType
import viaduct.service.api.spi.FlagManager
import viaduct.utils.slf4j.logger

/**
 * Tests for ViaductExecutionStrategy focusing on core execution functionality.
 *
 * This test class covers:
 * - Field resolution and data fetching behavior
 * - Field merging with and without arguments
 * - Error handling and instrumentation
 * - DataLoader batching capabilities
 * - Nested lists and DataFetcherResult handling
 * - Mutation field serial execution
 * - EngineResultLocalContext configuration
 *
 * For tests related to child plan execution and Required Selection Sets (RSS),
 * see ViaductExecutionStrategyChildPlanTest.
 *
 * For tests comparing modern vs classic execution strategies,
 * see ViaductExecutionStrategyModernTest.
 */
@ExperimentalCoroutinesApi
class ViaductExecutionStrategyTest {
    companion object {
        private val log by logger()
    }

    // Use a single-threaded dispatcher for deterministic testing of DataLoader batching.
    //
    // The multi-threaded default dispatcher (Dispatchers.Default) creates a race condition in this test:
    // some threads complete their work before others even start, causing the NextTickDispatcher's counter
    // to prematurely hit zero and trigger batching with only partial keys (instead of all 10).
    //
    // This is a TEST-ONLY issue due to instant batch operations. In production, DataLoader operations
    // are I/O-bound (database queries, API calls), giving plenty of time for all loads to register
    // before any batch completes. The batching logic being tested here is identical regardless of
    // thread count - we're just eliminating timing variance in the test fixture.
    @OptIn(ObsoleteCoroutinesApi::class)
    val nextTickDispatcher = NextTickDispatcher(
        wrappedDispatcher = kotlinx.coroutines.newSingleThreadContext("test-dispatcher"),
        flagManager = FlagManager.disabled
    )

    @Test
    fun `fatal error in data fetcher crashes request`() =
        runExecutionTest {
            val sdl = "type Query { field: String }"
            val resolvers = mapOf(
                "Query" to mapOf(
                    "field" to DataFetcher<String> {
                        // Throw Error, not Exception - represents fatal JVM error
                        throw AssertionError("Fatal invariant violation")
                    }
                )
            )

            val exception = assertThrows<AssertionError> {
                executeViaductModernGraphQL(sdl, resolvers, "{ field }")
            }

            assertTrue(
                exception.message?.contains("Fatal invariant violation") ==
                    true
            )
        }

    @Test
    fun `instrumentation failure during field completion is contained at field level`() =
        runExecutionTest {
            // Define a simple schema with one field that would normally resolve to a valid value.
            val sdl = // language=GraphQL
                """
                type Query {
                    brokenField: String
                }
                """
            val resolvers = mapOf(
                "Query" to mapOf(
                    // This resolver returns a valid value.
                    "brokenField" to DataFetcher { "Valid Value" }
                )
            )
            val query = // language=GraphQL
                """
                query {
                    brokenField
                }
                """

            // Define a custom instrumentation that fails during field completion.
            // We implement the ViaductModernInstrumentation.WithBeginFieldCompletion interface.
            class FailingFieldCompletionInstrumentation : ViaductModernInstrumentation.WithBeginFieldCompletion {
                override fun beginFieldCompletion(
                    parameters: InstrumentationFieldCompleteParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? =
                    object : InstrumentationContext<Any> {
                        override fun onDispatched() {
                            // No-op
                        }

                        override fun onCompleted(
                            result: Any?,
                            t: Throwable?
                        ) {
                            // Force a failure during field completion.
                            throw RuntimeException("Forced field completion error")
                        }
                    }
            }
            // Create a list of instrumentations containing our failing instrumentation.
            val instrumentations = listOf<ViaductModernInstrumentation>(FailingFieldCompletionInstrumentation())
            val schema = createSchema(sdl, resolvers)
            // Build the GraphQL engine with our schema, resolvers, and instrumentation.
            val graphQL = createViaductGraphQL(schema, instrumentations = instrumentations)
            val executionResult = executeQuery(schema, graphQL, query, emptyMap())
            // The field should resolve to null because the forced error is caught and converted
            // into a field-level error rather than aborting the whole query.
            val data = executionResult.getData<Map<String, Any?>>()
            assertNull(data?.get("brokenField"), "Expected brokenField to be null due to instrumentation failure")
            // The error list should contain an error message from our forced failure.
            val errorMessages = executionResult.errors.map { it.message }
            assertTrue(
                errorMessages.any { it.contains("Forced field completion error") },
                "Expected an error message containing 'Forced field completion error'"
            )
        }

    @Test
    fun `configures EngineResultLocalContext`() {
        var ctx: EngineResultLocalContext? = null
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val sdl = "type Query { field: Int }"
                val query = "{ field }"
                val resolvers = mapOf(
                    "Query" to mapOf(
                        "field" to DataFetcher {
                            ctx = it.getLocalContextForType<EngineResultLocalContext>()
                            0
                        }
                    ),
                )

                executeViaductModernGraphQL(sdl, resolvers, query)
                assertEquals("Query", ctx?.rootEngineResult?.graphQLObjectType?.name)
                assertEquals("Query", ctx?.parentEngineResult?.graphQLObjectType?.name)
                assertEquals("Query", ctx?.queryEngineResult?.graphQLObjectType?.name)
                // For Query operations, queryEngineResult should be the same instance as rootEngineResult
                assertSame(ctx!!.rootEngineResult, ctx!!.queryEngineResult)
            }
        }
    }

    @Test
    fun `configures EngineResultLocalContext for mutation operations`() {
        var ctx: EngineResultLocalContext? = null
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val sdl = """
                    type Query { field: Int }
                    type Mutation { mutateField: Int }
                """
                val query = "mutation { mutateField }"
                val resolvers = mapOf(
                    "Query" to mapOf(
                        "field" to DataFetcher { 0 }
                    ),
                    "Mutation" to mapOf(
                        "mutateField" to DataFetcher {
                            ctx = it.getLocalContextForType<EngineResultLocalContext>()
                            0
                        }
                    ),
                )

                executeViaductModernGraphQL(sdl, resolvers, query)
                assertEquals("Mutation", ctx?.rootEngineResult?.graphQLObjectType?.name)
                assertEquals("Mutation", ctx?.parentEngineResult?.graphQLObjectType?.name)
                assertEquals("Query", ctx?.queryEngineResult?.graphQLObjectType?.name)
                // For Mutation operations, queryEngineResult should be a separate Query-type instance
                assertNotSame(ctx!!.rootEngineResult, ctx!!.queryEngineResult)
            }
        }
    }

    @RepeatedTest(1000)
    fun `still allows dataloader batching`() =
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val sdl = // language=GraphQL
                    """
                    type Query {
                        foo: [Foo]
                    }
                    type Foo {
                        bar: String
                    }
                    """

                val query = // language=GraphQL
                    """
                    query {
                        foo {
                            bar
                        }
                    }
                    """

                val loadCalls = mutableListOf<Set<*>>()
                val loader = InternalDataLoader.newMappedLoader<Int, String, Any>(
                    object : MappedBatchLoadFn<Int, String> {
                        override suspend fun load(
                            keys: Set<Int>,
                            env: BatchLoaderEnvironment<Int>
                        ): Map<Int, String> {
                            loadCalls.add(keys)
                            return keys.associateWith { "$it" }
                        }
                    }
                )

                data class Bar(
                    val intValue: Int
                )

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "foo" to DataFetcher { (1..10).map { Bar(it) } }
                    ),
                    "Foo" to mapOf(
                        "bar" to DataFetcher {
                            scopedFuture {
                                val source = it.getSource<Bar>()!!
                                try {
                                    loader.load(source.intValue)
                                } catch (e: Exception) {
                                    println(e)
                                }
                            }
                        }
                    )
                )

                executeViaductModernGraphQL(sdl, resolvers, query)
                assertEquals(1, loadCalls.size)
                assertEquals(listOf(setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)), loadCalls)
            }
        }

    @Test
    fun `test field merging with no arguments`() =
        runExecutionTest {
            val sdl = // language=GraphQL
                """
                type Query {
                    foo: Foo
                }
                type Foo {
                    bar: Bar
                    baz: String
                }
                type Bar {
                    one: String
                    two: String
                    nestedBar: Bar
                }
                """

            val query = // language=GraphQL
                """
                query {
                    foo {
                        bar {
                            one
                            nestedBar {
                                two
                            }
                        }
                        bar {
                            one
                            two
                        }
                        bar {
                            two
                            nestedBar {
                                one
                                two
                            }
                        }
                        baz
                    }
                }
                """

            val barCount = AtomicInteger(0)
            val oneCount = AtomicInteger(0)
            val twoCount = AtomicInteger(0)
            val resolvers = mapOf(
                "Query" to mapOf(
                    "foo" to DataFetcher { mapOf<String, Any?>() }
                ),
                "Foo" to mapOf(
                    "bar" to DataFetcher {
                        scopedFuture {
                            barCount.incrementAndGet()
                            mapOf<String, Any?>()
                        }
                    },
                    "baz" to DataFetcher { scopedFuture { "baz" } }
                ),
                "Bar" to mapOf(
                    "one" to DataFetcher {
                        scopedFuture {
                            oneCount.incrementAndGet()
                            "one"
                        }
                    },
                    "two" to DataFetcher {
                        scopedFuture {
                            twoCount.incrementAndGet()
                            "two"
                        }
                    },
                    "nestedBar" to DataFetcher { mapOf<String, Any?>() }
                )
            )

            val modernResult = executeViaductModernGraphQL(sdl, resolvers, query)

            assertEquals(
                mapOf(
                    "foo" to mapOf(
                        "bar" to mapOf(
                            "one" to "one",
                            "nestedBar" to mapOf("one" to "one", "two" to "two"),
                            "two" to "two"
                        ),
                        "baz" to "baz"
                    )
                ),
                modernResult.getData<Map<String, Any?>>()
            )
            assertEquals(1, barCount.get())
            // once in bar, once in nestedBar
            assertEquals(2, oneCount.get())
            assertEquals(2, twoCount.get())
        }

    @Test
    fun `test field merging with simple arguments`() =
        runExecutionTest {
            val sdl = // language=GraphQL
                """
                type Query {
                    foo: Foo
                }
                type Foo {
                    bar(arg: Int): Bar
                    baz: String
                }
                type Bar {
                    value(multiplier: Int): Int
                    otherValue: String
                }
                """

            val query = // language=GraphQL
                """
                query {
                    foo {
                        bar(arg: 5) {
                            value(multiplier: 2)
                            value(multiplier: 2)  # Same arguments, can be merged
                            otherValue
                        }
                        # same arg as above selection, can be merged
                        bar(arg: 5) {
                            value2: value(multiplier: 3)  # Different multiplier
                            otherValue
                        }
                        # Different arg value - cannot be merged
                        bar2: bar(arg: 10) {
                            value(multiplier: 2)
                            otherValue
                        }
                        baz
                    }
                }
                """

            val barCount = AtomicInteger(0)
            val valueCount = AtomicInteger(0)
            val resolvers = mapOf(
                "Query" to mapOf(
                    "foo" to DataFetcher { mapOf<String, Any?>() }
                ),
                "Foo" to mapOf(
                    "bar" to DataFetcher { env ->
                        scopedFuture {
                            val arg = env.getArgument<Int>("arg")
                            barCount.incrementAndGet()
                            mapOf("inputArg" to arg)
                        }
                    },
                    "baz" to DataFetcher { scopedFuture { "baz" } }
                ),
                "Bar" to mapOf(
                    "value" to DataFetcher { env ->
                        scopedFuture {
                            valueCount.incrementAndGet()
                            val inputArg = env.getSource<Map<String, Int>>()?.get("inputArg") ?: 0
                            val multiplier = env.getArgument<Int>("multiplier")!!
                            inputArg * multiplier
                        }
                    },
                    "otherValue" to DataFetcher { "constant" }
                )
            )

            val modernResult = executeViaductModernGraphQL(sdl, resolvers, query)

            assertEquals(
                mapOf(
                    "foo" to mapOf(
                        "bar" to mapOf(
                            "value" to 10, // 5 * 2
                            "value2" to 15, // 5 * 3
                            "otherValue" to "constant"
                        ),
                        "bar2" to mapOf(
                            "value" to 20, // 10 * 2
                            "otherValue" to "constant"
                        ),
                        "baz" to "baz"
                    )
                ),
                modernResult.getData<Map<String, Any?>>()
            )

            // bar should be called twice - once for arg:5 (memoized) and once for arg:10
            assertEquals(2, barCount.get())

            // value should be called 4 times:
            // - Once for bar1.value1 with multiplier:2 (memoized for value2)
            // - Once for bar2.value with multiplier:3
            // - Once for bar3.value with multiplier:2 (different input arg)
            assertEquals(3, valueCount.get())
        }

    @Test
    fun `test field merging with complex argument types`() =
        runExecutionTest {
            val sdl = // language=GraphQL
                """
            type Query {
                foo: Foo
            }

            type Foo {
                # Test different primitive types
                stringArg(value: String): String
                intArg(value: Int): Int
                floatArg(value: Float): Float
                booleanArg(value: Boolean): Boolean

                # Test enum type
                enumArg(value: TestEnum): String

                # Test input object type
                complexArg(input: ComplexInput): String

                # Test array type
                arrayArg(values: [Int]): [Int]

                # Test object with multiple args
                multiArg(str: String, num: Int): String
            }

            enum TestEnum {
                ONE
                TWO
            }

            input ComplexInput {
                name: String
                count: Int
                tags: [String]
            }
            """

            val query = // language=GraphQL
                """
            query {
                foo {
                    str1:stringArg(value: "hello")
                    str1:stringArg(value: "hello")        # same arg, can be merged with str1
                    str2:stringArg(value: "different")    # diff arg, cannot merge and requires diff. response key

                    int1: intArg(value: 42)
                    int1: intArg(value: 42)               # same arg, can be merged with int1
                    int2: intArg(value: 43)               # diff arg, cannot merge and requires diff. response key

                    float1: floatArg(value: 3.14)
                    float1: floatArg(value: 3.14)         # same arg, can be merged with float1
                    float2: floatArg(value: 3.15)         # diff arg, cannot be merged with float2

                    bool1: booleanArg(value: true)
                    bool1: booleanArg(value: true)        # same arg, can be merged with bool1
                    bool2: booleanArg(value: false)       # diff arg, cannot be merged with bool2

                    enum1: enumArg(value: ONE)
                    enum1: enumArg(value: ONE)            # same arg, can be merged with enum1
                    enum2: enumArg(value: TWO)            # diff arg, cannot merge and requires diff. response key

                    complex1: complexArg(input: {name: "test", count: 1, tags: ["a", "b"]})
                    # Same arg - can be merged with complex1
                    complex1: complexArg(input: {name: "test", count: 1, tags: ["a", "b"]})
                    # Diff arg, cannot merge
                    complex2: complexArg(input: {name: "test", count: 2, tags: ["a", "b"]})

                    array1: arrayArg(values: [1, 2, 3])
                    array1: arrayArg(values: [1, 2, 3])    # same arg, can be merged
                    array2: arrayArg(values: [1, 2, 4])    # diff arg, cannot merge

                    multi1: multiArg(str: "test", num: 1)
                    multi1: multiArg(str: "test", num: 1)  # same arg, can be merged
                    multi2: multiArg(str: "test", num: 2)  # diff arg, cannot merge
                }
            }
            """

            // Counters for each resolver type
            val counts = mutableMapOf<String, AtomicInteger>()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "foo" to DataFetcher { mapOf<String, Any?>() }
                ),
                "Foo" to mapOf(
                    "stringArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("string") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<String>("value")
                        }
                    },
                    "intArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("int") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<Int>("value")
                        }
                    },
                    "floatArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("float") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<Double>("value")
                        }
                    },
                    "booleanArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("boolean") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<Boolean>("value")
                        }
                    },
                    "enumArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("enum") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<String>("value")
                        }
                    },
                    "complexArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("complex") { AtomicInteger(0) }.incrementAndGet()
                            val input = env.getArgument<Map<String, Any>>("input")!!
                            "${input["name"]}-${input["count"]}"
                        }
                    },
                    "arrayArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("array") { AtomicInteger(0) }.incrementAndGet()
                            env.getArgument<List<Int>>("values")
                        }
                    },
                    "multiArg" to DataFetcher { env ->
                        scopedFuture {
                            counts.getOrPut("multi") { AtomicInteger(0) }.incrementAndGet()
                            "${env.getArgument<String>("str")}-${env.getArgument<Int>("num")}"
                        }
                    }
                )
            )

            val modernResult = executeViaductModernGraphQL(sdl, resolvers, query)

            val data = modernResult.getData<Map<String, Any?>>()
            assertEquals(
                mapOf(
                    "foo" to mapOf(
                        "str1" to "hello",
                        "str2" to "different",
                        "int1" to 42,
                        "int2" to 43,
                        "float1" to 3.14,
                        "float2" to 3.15,
                        "bool1" to true,
                        "bool2" to false,
                        "enum1" to "ONE",
                        "enum2" to "TWO",
                        "complex1" to "test-1",
                        "complex2" to "test-2",
                        "array1" to listOf(1, 2, 3),
                        "array2" to listOf(1, 2, 4),
                        "multi1" to "test-1",
                        "multi2" to "test-2"
                    )
                ),
                data
            )

            // Verify resolver call counts
            assertEquals(2, counts["string"]?.get(), "String resolver should be called twice")
            assertEquals(2, counts["int"]?.get(), "Int resolver should be called twice")
            assertEquals(2, counts["float"]?.get(), "Float resolver should be called twice")
            assertEquals(2, counts["boolean"]?.get(), "Boolean resolver should be called twice")
            assertEquals(2, counts["enum"]?.get(), "Enum resolver should be called twice")
            assertEquals(2, counts["complex"]?.get(), "Complex resolver should be called twice")
            assertEquals(2, counts["array"]?.get(), "Array resolver should be called twice")
            assertEquals(2, counts["multi"]?.get(), "Multi-arg resolver should be called twice")
        }

    @Test
    fun `nested lists of DataFetcherResult are handled correctly`() =
        runExecutionTest {
            val sdl = // language=GraphQL
                """
                type Query {
                    matrix: [[MatrixItem]]
                }
                type MatrixItem {
                    value: Int
                    errorProneValue: Int
                }
                """
            val resolvers = mapOf(
                "Query" to mapOf(
                    "matrix" to DataFetcher {
                        listOf(
                            listOf(
                                DataFetcherResult
                                    .newResult<Map<String, Any?>>()
                                    .data(mapOf("value" to 1))
                                    .build(),
                                DataFetcherResult
                                    .newResult<Map<String, Any?>>()
                                    .error(
                                        GraphQLError
                                            .newError()
                                            .message("Error at [0][1]")
                                            .path(listOf("matrix", 0, 1))
                                            .build()
                                    ).build()
                            ),
                            listOf(
                                DataFetcherResult
                                    .newResult<Map<String, Any?>>()
                                    .data(mapOf("value" to 3))
                                    .build(),
                                DataFetcherResult
                                    .newResult<Map<String, Any?>>()
                                    .data(mapOf("value" to 4))
                                    .build()
                            )
                        )
                    }
                ),
                "MatrixItem" to mapOf(
                    "errorProneValue" to DataFetcher { env ->
                        val value = env.getSource<Map<String, Int>>()?.get("value") ?: 0
                        if (value % 2 == 0) {
                            DataFetcherResult
                                .newResult<Int>()
                                .error(
                                    GraphQLError
                                        .newError()
                                        .message("Even value error at value: $value")
                                        .path(env.executionStepInfo.path.toList())
                                        .build()
                                ).build()
                        } else {
                            DataFetcherResult
                                .newResult<Int>()
                                .data(value)
                                .build()
                        }
                    }
                )
            )
            val query = // language=GraphQL
                """
                query {
                    matrix {
                        value
                        errorProneValue
                    }
                }
                """

            val modernResult = executeViaductModernGraphQL(sdl, resolvers, query)
            log.debug("Modern result: {}", modernResult.toSpecification())

            // Expected data
            val expectedData = mapOf(
                "matrix" to listOf(
                    listOf(
                        mapOf("value" to 1, "errorProneValue" to 1),
                        null,
                    ),
                    listOf(
                        mapOf("value" to 3, "errorProneValue" to 3),
                        mapOf("value" to 4, "errorProneValue" to null)
                    )
                )
            )

            // Verify data
            assertEquals(expectedData, modernResult.getData<Map<String, Any?>>())

            // Verify errors
            val expectedErrors = listOf(
                GraphQLError
                    .newError()
                    .message("Error at [0][1]")
                    .path(listOf("matrix", 0, 1))
                    .build(),
                GraphQLError
                    .newError()
                    .message("Even value error at value: 4")
                    .path(listOf("matrix", 1, 1, "errorProneValue"))
                    .build()
            )

            // Compare errors (comparing messages and paths)
            val modernErrors = modernResult.errors.map { it.message to it.path }
            val expectedErrorsData = expectedErrors.map { it.message to it.path }

            assertEquals(expectedErrorsData.size, modernErrors.size)
            modernErrors.forEach { error ->
                assertTrue(expectedErrorsData.contains(error))
            }
        }

    @Test
    fun `test instrumentation methods are called and callbacks are verified`() =
        runExecutionTest {
            val sdl = // language=GraphQL
                """
                type Query {
                    greeting: String
                    farewell: String
                }
                """

            val resolvers = mapOf(
                "Query" to mapOf(
                    "greeting" to DataFetcher { "Hello, World!" },
                    "farewell" to DataFetcher { "Goodbye, World!" }
                )
            )

            val schema = createSchema(sdl, resolvers)
            val recordingInstrumentation = RecordingInstrumentation()
            val graphQL = createViaductGraphQL(schema, instrumentations = listOf(recordingInstrumentation))

            val query = // language=GraphQL
                """
                query {
                    greeting
                    farewell
                }
                """

            val executionResult = executeQuery(schema, graphQL, query, emptyMap())

            // Assertions
            assertTrue(executionResult.errors.isEmpty())
            assertEquals(
                mapOf("greeting" to "Hello, World!", "farewell" to "Goodbye, World!"),
                executionResult.getData<Map<String, Any>>()
            )

            // Verify that instrumentation methods were called and callbacks were invoked

            // Fetch Object
            assertEquals(1, recordingInstrumentation.fetchObjectContexts.size)
            val fetchObjectContext = recordingInstrumentation.fetchObjectContexts.first()
            assertTrue(fetchObjectContext.onDispatchedCalled.get())
            assertTrue(fetchObjectContext.onCompletedCalled.get())
            assertNull(fetchObjectContext.completedException)
            val fetchObjectData = fetchObjectContext.completedValue
            assertNotNull(fetchObjectData)
            // We can further verify the data if needed

            // Field Execution
            assertEquals(2, recordingInstrumentation.fieldExecutionContexts.size)
            recordingInstrumentation.fieldExecutionContexts.forEach { context ->
                assertTrue(context.onDispatchedCalled.get())
                assertTrue(context.onCompletedCalled.get())
                assertNull(context.completedException)
                // Optionally verify completedValue
            }

            // Field Fetching
            assertEquals(2, recordingInstrumentation.fieldFetchingContexts.size)
            recordingInstrumentation.fieldFetchingContexts.forEach { context ->
                assertTrue(context.onDispatchedCalled.get())
                assertTrue(context.onCompletedCalled.get())
                assertNull(context.completedException)
                // Optionally verify completedValue
            }

            // Complete Object
            assertEquals(1, recordingInstrumentation.completeObjectContexts.size)
            val completeObjectContext = recordingInstrumentation.completeObjectContexts.first()
            assertTrue(completeObjectContext.onDispatchedCalled.get())
            assertTrue(completeObjectContext.onCompletedCalled.get())
            assertNull(completeObjectContext.completedException)

            // Field Completion
            assertEquals(2, recordingInstrumentation.fieldCompletionContexts.size)
            recordingInstrumentation.fieldCompletionContexts.forEach { context ->
                assertTrue(context.onDispatchedCalled.get())
                assertTrue(context.onCompletedCalled.get())
                assertNull(context.completedException)
            }
        }

    @Test
    fun `mutation fields are resolved serially`() {
        // If this test fails it will probably be easier to debug with count decreased to a reasonable value like 10.
        // But please keep the checked-in value high.
        // val count = 10_000
        val count = 1_000
        val counter = AtomicInteger(0)

        runExecutionTest {
            // Mutation.x accepts an argument though it isn't used by this test.
            // The presence of arguments are used to force a new execution of the resolver,
            // rather than using a cached entry.
            // This is to work around an issue at the time this test was written, where we will
            // reuse previous resolver executions.
            val sdl = """
                type Query { empty: Int }
                type Mutation { x(i:Int): Int }
            """.trimIndent()

            val resolvers = mapOf(
                "Mutation" to mapOf("x" to DataFetcher { counter.getAndIncrement() })
            )

            // build up an operation that looks like:
            // mutation {
            //   x_0: x(i:0)
            //   x_1: x(i:1)
            //   ...
            // }
            val query = buildString {
                append("mutation {")
                repeat(count) { i ->
                    append("\nx_$i:x(i:$i)")
                }
                append("\n}")
            }

            // build up map that looks like
            // mapOf(
            //   "x_0" to 0,
            //   "x_1" to 1,
            //   ...
            // )
            val expectedData = mutableMapOf<String, Any?>().let { map ->
                repeat(count) { i -> map.put("x_$i", i) }
                map.toMap()
            }

            val schema = createSchema(sdl, resolvers)
            val graphQL = createViaductGraphQL(schema)
            val executionResult = executeQuery(schema, graphQL, query, emptyMap())

            assertEquals(expectedData, executionResult.getData<Map<String, Any?>>())
            assertTrue(executionResult.errors.isEmpty())
        }
    }

    @Test
    fun `mutation field resolver throws an exception`() {
        runExecutionTest {
            val schema = createSchema(
                """
                   type Query { empty: Int }
                   type Mutation { x: Int }
                """.trimIndent(),
                mapOf(
                    "Mutation" to mapOf("x" to DataFetcher { throw RuntimeException("error!") })
                )
            )
            val graphQL = createViaductGraphQL(schema)
            val executionResult = executeQuery(schema, graphQL, "mutation { x }", emptyMap())

            assertEquals(mapOf("x" to null), executionResult.getData<Map<String, Any?>>())
            assertEquals(1, executionResult.errors.size)
            val error = executionResult.errors[0]
            assertTrue(error.message.contains("Exception while fetching data (/x)"))
            assertTrue(error.message.contains("error!"))
        }
    }

    @Test
    fun `withRequestSupervisor integration - cleans up lingering child plan jobs after query execution`() =
        runExecutionTest {
            withContext(nextTickDispatcher) {
                val sdl = """
                    type Query {
                        mainField: String
                        hangingField: String
                    }
                """
                // Query only asks for mainField, not hangingField
                val query = """
                    query {
                        mainField
                    }
                """

                val hangingJobLaunched = CompletableDeferred<Unit>()
                val hangingJobCancelled = CompletableDeferred<Throwable?>()

                val resolvers = mapOf(
                    "Query" to mapOf(
                        "mainField" to DataFetcher { "main" },
                        // This field is part of the child plan (RSS) but not directly queried
                        "hangingField" to DataFetcher {
                            scopedFuture {
                                launch {
                                    hangingJobLaunched.complete(Unit)
                                    delay(Long.MAX_VALUE)
                                }.invokeOnCompletion { cause ->
                                    hangingJobCancelled.complete(cause)
                                }
                                // Return immediately - the job runs on parent scope
                                "hanging"
                            }
                        }
                    )
                )

                // Configure RSS so that mainField triggers a child plan that includes hangingField
                val requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Query" to "mainField", "fragment Main on Query { hangingField }")
                    .build()

                val result = executeViaductModernGraphQL(
                    sdl = sdl,
                    resolvers = resolvers,
                    query = query,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry
                )

                // Query should complete successfully (doesn't wait for child plan)
                assertEquals(
                    mapOf("mainField" to "main"),
                    result.getData<Map<String, Any?>>()
                )
                assertTrue(result.errors.isEmpty())

                // The hanging child plan job should be cancelled after execution completes
                val cause = withTimeout(1000) {
                    hangingJobCancelled.await()
                }
                assertNotNull(cause)
                assertInstanceOf(RequestScopeCancellationException::class.java, cause)
                Unit
            }
        }

    @Test
    fun `mutation operation throws multiple field exceptions`() {
        runExecutionTest {
            val schema = createSchema(
                """
                    type Query { empty: Int }
                    type Mutation { x:Int, y:Int, z:Int }
                """.trimIndent(),
                mapOf(
                    "Mutation" to mapOf(
                        "x" to DataFetcher { throw RuntimeException("error!") },
                        "y" to DataFetcher { throw RuntimeException("error!") },
                        "z" to DataFetcher { throw RuntimeException("error!") },
                    )
                )
            )
            val graphQL = createViaductGraphQL(schema)

            val executionResult = executeQuery(schema, graphQL, "mutation { x y z }", emptyMap())
            listOf("x", "y", "z").forEach { key ->
                val data = executionResult.getData<Map<String, Any?>>()
                assertTrue(data.containsKey(key))
                assertNull(data[key])
                val error = executionResult.errors.find { it.path.last() == key }
                assertNotNull(error)
                assertTrue(error!!.message.contains("Exception while fetching data (/$key)"))
            }
        }
    }

    @Nested
    inner class WithRequestSupervisorTests {
        private fun createTestStrategy() =
            ViaductExecutionStrategy(
                dataFetcherExceptionHandler = SimpleDataFetcherExceptionHandler(),
                executionParametersFactory = ExecutionParameters.Factory(
                    requiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
                    fieldCheckerDispatcherRegistry = FieldCheckerDispatcherRegistry.Empty,
                    typeCheckerDispatcherRegistry = TypeCheckerDispatcherRegistry.Empty,
                    flagManager = FlagManager.default
                ),
                accessCheckRunner = AccessCheckRunner(DefaultCoroutineInterop),
                isSerial = false
            )

        @Test
        fun `cancels child jobs after block completes`() =
            runExecutionTest {
                val strategy = createTestStrategy()
                val childWasCancelled = CompletableDeferred<Unit>()

                val result = strategy.withRequestSupervisor { supervisorScopeFactory ->
                    // Launch on supervisor (sibling to async, not child)
                    supervisorScopeFactory(coroutineContext).launch {
                        delay(Long.MAX_VALUE)
                    }.invokeOnCompletion { cause ->
                        if (cause is kotlinx.coroutines.CancellationException) {
                            childWasCancelled.complete(Unit)
                        }
                    }
                    "success"
                }

                assertEquals("success", result)

                // Wait for cancellation to propagate
                withTimeout(1000) {
                    childWasCancelled.await()
                }
            }

        @Test
        fun `cancels supervisor even when block throws exception`() =
            runExecutionTest {
                val strategy = createTestStrategy()
                val childWasCancelled = CompletableDeferred<Unit>()

                val exception = RuntimeException("test exception")

                val thrown = assertThrows<RuntimeException> {
                    runBlocking {
                        withThreadLocalCoroutineContext {
                            strategy.withRequestSupervisor { supervisorScopeFactory ->
                                // Launch on supervisor (sibling to async, not child)
                                supervisorScopeFactory(coroutineContext).launch {
                                    delay(Long.MAX_VALUE)
                                }.invokeOnCompletion { cause ->
                                    if (cause is kotlinx.coroutines.CancellationException) {
                                        childWasCancelled.complete(Unit)
                                    }
                                }
                                throw exception
                            }
                        }
                    }
                }

                assertEquals("test exception", thrown.message)

                // Wait for cancellation to propagate
                withTimeout(1000) {
                    childWasCancelled.await()
                }
            }

        @Test
        fun `handles self-cancellation of the block`() =
            runExecutionTest {
                val strategy = createTestStrategy()
                val childLaunched = CompletableDeferred<Unit>()
                val childWasCancelled = CompletableDeferred<Unit>()

                val ex = assertThrows<kotlinx.coroutines.CancellationException> {
                    runBlocking {
                        withThreadLocalCoroutineContext {
                            strategy.withRequestSupervisor { supervisorScopeFactory ->
                                // Launch on supervisor (sibling to async, not child)
                                val job = supervisorScopeFactory(coroutineContext).launch {
                                    childLaunched.complete(Unit)
                                    delay(Long.MAX_VALUE)
                                }
                                job.invokeOnCompletion { cause ->
                                    if (cause is kotlinx.coroutines.CancellationException) {
                                        childWasCancelled.complete(Unit)
                                    }
                                }
                                childLaunched.await()
                                throw kotlinx.coroutines.CancellationException("self-cancel")
                            }
                        }
                    }
                }

                assertEquals("self-cancel", ex.message)

                // Wait for cancellation to propagate
                withTimeout(1000) {
                    childWasCancelled.await()
                }
            }

        @Test
        fun `cancellation propagates through multiple job levels`() =
            runExecutionTest {
                val strategy = createTestStrategy()
                val grandchildWasCancelled = CompletableDeferred<Unit>()

                val result = strategy.withRequestSupervisor { supervisorScopeFactory ->
                    // Launch child on supervisor (sibling to async, not child)
                    // This way it doesn't block the async from completing
                    supervisorScopeFactory(coroutineContext).launch {
                        // Launch grandchild that would hang without cancellation
                        launch {
                            delay(Long.MAX_VALUE)
                        }.invokeOnCompletion { cause ->
                            if (cause is kotlinx.coroutines.CancellationException) {
                                grandchildWasCancelled.complete(Unit)
                            }
                        }
                    }
                    "done"
                }

                assertEquals("done", result)

                // Wait for cancellation to propagate through all levels
                withTimeout(1000) {
                    grandchildWasCancelled.await()
                }
            }

        @Test
        fun `request supervisor cancellation uses RequestScopeCancellationException`() =
            runExecutionTest {
                val strategy = createTestStrategy()
                val cancellationCause = CompletableDeferred<Throwable?>()

                runBlocking {
                    withThreadLocalCoroutineContext {
                        strategy.withRequestSupervisor { supervisorScopeFactory ->
                            supervisorScopeFactory(coroutineContext).launch {
                                delay(Long.MAX_VALUE)
                            }.invokeOnCompletion { cause ->
                                cancellationCause.complete(cause)
                            }
                        }
                    }
                }

                val cause = withTimeout(1000) {
                    cancellationCause.await()
                }

                assertNotNull(cause)
                assertInstanceOf(RequestScopeCancellationException::class.java, cause)
            }
    }
}
