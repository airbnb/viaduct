package viaduct.tenant.runtime.featuretests

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
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.graphql.test.assertJson as mapAssertJson
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.EnumType
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class FieldResolverTest {
    @Test
    fun `query field resolver throws an exception`() =
        FeatureTestBuilder("extend type Query { x: Int }")
            .resolver("Query" to "x") { throw RuntimeException("error!") }
            .build()
            .assertJson(
                """
                {
                    data: { x: null },
                    errors: [{
                        message: "java.lang.RuntimeException: error!",
                        locations: [{ line: 1, column: 3 }],
                        path: ["x"],
                        extensions: {
                            fieldName: "x",
                            parentType: "Query",
                            classification: "DataFetchingException"
                        }
                    }]
                }
                """.trimIndent(),
                "{ x }"
            )

    @Test
    fun `subscription field resolver throws an exception`() =
        FeatureTestBuilder("extend type Query { empty: Int } extend type Subscription { x: Int }")
            .resolver("Subscription" to "x") { throw RuntimeException("error!") }
            .build()
            .assertJson(
                """
                {
                    data: { x: null },
                    errors: [{
                        message: "java.lang.RuntimeException: error!",
                        locations: [{ line: 1, column: 16 }],
                        path: ["x"],
                        extensions: {
                            fieldName: "x",
                            parentType: "Subscription",
                            classification: "DataFetchingException"
                        }
                    }]
                }
                """.trimIndent(),
                "subscription { x }"
            )

    @Test
    fun `can query a document multiple times with different variables`() =
        // regression, see https://app.asana.com/0/1208357307661305/1209886139365688
        FeatureTestBuilder("extend type Query { x:Int, y:Int }")
            .resolver("Query" to "x") { 2 }
            .resolver("Query" to "y") { 3 }
            .build()
            .apply {
                val query = """
                    query Q(${'$'}include_x: Boolean!, ${'$'}include_y: Boolean!) {
                      x @include(if:${'$'}include_x)
                      y @include(if:${'$'}include_y)
                    }
                """.trimIndent()
                assertJson("{data: {x:2}}", query, mapOf("include_x" to true, "include_y" to false))
                assertJson("{data: {y:3}}", query, mapOf("include_x" to false, "include_y" to true))
            }
            .let { Unit } // return Unit

    @Test
    fun `accessing field on node reference throws`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "baz") { ctx ->
                val id = ctx.globalIDFor(Baz.Reflection, "1")
                ctx.nodeFor(ctx.globalIDFor(Baz.Reflection, "1")).also {
                    assertEquals(id, it.getId())
                    it.getX() // This should throw
                }
            }
            .build()
            .execute("{baz { id }}")
            .apply {
                assertEquals(1, errors.size)
                val error = errors[0]
                assertTrue(error.message.contains("Attempted to access field Baz.x but it was not set: only id can be accessed on an unresolved Node reference"))
            }.getData<Map<String, Any?>>().mapAssertJson("{baz: null}")

    @Test
    fun `resolver can read and write enum values`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "enumField") { EnumType.A }
            .resolver(
                "Query" to "string1",
                objectValueFragment = "enumField",
                resolveFn = { ctx: UntypedFieldContext ->
                    val enumFieldValue = ctx.objectValue.get<EnumType>("enumField")
                    assertEquals(EnumType.A, enumFieldValue)
                    enumFieldValue.name.lowercase()
                }
            )
            .build()
            .execute("{string1}")
            .assertJson("{data: {string1: \"a\"}}")

    @Test
    fun `resolver can read and write globalid values`() =
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver("Query" to "idField") { ctx ->
                // resolver returns a GRT value to the tenant runtime, which we expect to be unwrapped before
                // it's handed over to the engine
                ctx.globalIDFor(Baz.Reflection, "1")
            }
            .resolver(
                "Query" to "string1",
                objectValueFragment = "idField",
                resolveFn = { ctx: UntypedFieldContext ->
                    // using an UntypedFieldContext, peek at the engine data for the field to ensure that it's
                    // been unwrapped
                    val idFieldValue = ctx.objectValue.get<GlobalID<Baz>>("idField")
                    assertEquals(Baz.Reflection, idFieldValue.type)
                    assertEquals("1", idFieldValue.internalID)
                    idFieldValue.internalID
                }
            )
            .build()
            .execute("{string1}")
            .assertJson("{data: {string1: \"1\"}}")

    @Test
    @DisplayName("ensure fetchObject onCompleted is called after all fields are fetched")
    fun fetchObjectCompletedAfterFieldsFetched() {
        // Latch that reaches 0 only after both field resolvers complete.
        val resolversDone = CountDownLatch(2)
        // Latch to confirm instrumentation was invoked at all.
        val instrumentationBegun = CountDownLatch(1)
        // Latch to synchronize whenCompleted callback execution
        val onCompletedCalled = CountDownLatch(1)
        // Records whether onCompleted observed resolversDone already at 0.
        var onCompletedAfterAllFields = false

        FeatureTestBuilder(
            FeatureTestSchemaFixture.sdl,
            instrumentation = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFetchObject {
                override fun beginFetchObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Unit> {
                    // Mark that instrumentation began for this fetchObject.
                    instrumentationBegun.countDown()
                    return SimpleInstrumentationContext.whenCompleted { _, _ ->
                        // onCompleted: check if both field resolvers had already finished.
                        // await with 0 timeout returns true iff the latch is already at 0.
                        onCompletedAfterAllFields = resolversDone.await(0, TimeUnit.MILLISECONDS)
                        onCompletedCalled.countDown()
                    }
                }
            }.asStandardInstrumentation
        )
            .resolver("Query" to "idField") { ctx ->
                // Returns a GRT value that should be unwrapped before engine execution.
                ctx.globalIDFor(Baz.Reflection, "1")
            }
            .resolver(
                "Query" to "string1",
                objectValueFragment = "idField",
                resolveFn = { _: UntypedFieldContext ->
                    // Simulate work; then signal completion for this field.
                    sleep(300)
                    resolversDone.countDown()
                    "1"
                }
            )
            .resolver(
                "Query" to "string2",
                objectValueFragment = "idField",
                resolveFn = { _: UntypedFieldContext ->
                    sleep(300)
                    resolversDone.countDown()
                    "2"
                }
            )
            .build()
            .execute("{string1, string2}")
            .assertJson("{data: {string1: \"1\", string2: \"2\"}}")

        assertTrue(instrumentationBegun.await(1, TimeUnit.SECONDS)) {
            "fetchObject instrumentation is never called."
        }
        assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) {
            "fetchObject onCompleted callback was never invoked."
        }
        assertTrue(onCompletedAfterAllFields) {
            "fetchObject onCompleted was called before all field resolvers completed."
        }
    }

    @Test
    @DisplayName("ensure fetchObjectSerially onCompleted is called after all fields are fetched")
    fun fetchObjectSeriallyCompletedAfterFieldsFetched() {
        val resolverBegun = AtomicInteger(0)
        // Latch that reaches 0 only after both field resolvers complete.
        val resolversDone = CountDownLatch(2)
        // Latch to confirm instrumentation was invoked at all.
        val instrumentationBegun = CountDownLatch(1)
        // Latch to synchronize whenCompleted callback execution
        val onCompletedCalled = CountDownLatch(1)
        // Records whether onCompleted observed resolversDone already at 0.
        var onCompletedAfterAllFields = false

        CountDownLatch(2)
        FeatureTestBuilder(
            FeatureTestSchemaFixture.sdl,
            instrumentation = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFetchObject {
                override fun beginFetchObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Unit> {
                    // Mark that instrumentation began for this fetchObject.
                    instrumentationBegun.countDown()
                    return SimpleInstrumentationContext.whenCompleted { _, _ ->
                        // onCompleted: check if both field resolvers had already finished.
                        // await with 0 timeout returns true iff the latch is already at 0.
                        onCompletedAfterAllFields = resolversDone.await(0, TimeUnit.MILLISECONDS)
                        onCompletedCalled.countDown()
                    }
                }
            }.asStandardInstrumentation
        )
            .resolver("Query" to "string1") { "InitialValue" }
            .resolver(
                "Mutation" to "string1",
                { _: UntypedFieldContext ->
                    resolverBegun.incrementAndGet()
                    // Simulate work; then signal completion for this field.
                    sleep(300)
                    resolversDone.countDown()
                    resolverBegun.get().toString()
                },
                queryValueFragment = "string1"
            )
            .resolver(
                "Mutation" to "string2",
                { _: UntypedFieldContext ->
                    resolverBegun.incrementAndGet()
                    // Simulate work; then signal completion for this field.
                    sleep(300)
                    resolversDone.countDown()
                    resolverBegun.get().toString()
                },
                queryValueFragment = "string1"
            )
            .build()
            .assertJson(
                "{data: {string1: \"1\", string2: \"2\"}}",
                "mutation { string1, string2 }"
            )

        assertTrue(instrumentationBegun.await(1, TimeUnit.SECONDS)) {
            "fetchObject instrumentation is never called."
        }
        assertTrue(onCompletedCalled.await(1, TimeUnit.SECONDS)) {
            "fetchObject onCompleted callback was never invoked."
        }
        assertTrue(onCompletedAfterAllFields) {
            "fetchObject onCompleted was called before all field resolvers completed."
        }
    }

    @Test
    @DisplayName("mutation returning list with failed field check should handle result type correctly")
    fun mutationReturningListWithFailedFieldCheck() =
        FeatureTestBuilder(
            """
            extend type Mutation {
                getUrls: [String!]
            }
            """.trimIndent()
        )
            .mutation("Mutation" to "getUrls") {
                // This mutator would return a list
                listOf("url1", "url2", "url3")
            }
            .fieldChecker(
                "Mutation" to "getUrls",
                "privacy-check",
                { _, _ ->
                    // Simulate a privacy check failure by throwing an exception
                    throw IllegalAccessException("Privacy check failed: user not authorized")
                }
            )
            .build()
            .execute("mutation { getUrls }")
            .apply {
                // We expect the mutation to fail gracefully with an error, not crash with a type mismatch
                assertEquals(1, errors.size, "Expected exactly one error from failed field check")
                val error = errors[0]
                assertTrue(
                    error.message.contains("Privacy check failed") || error.message.contains("not authorized"),
                    "Expected error message to contain privacy failure info, got: ${error.message}"
                )
            }.getData<Map<String, Any?>>().mapAssertJson("{getUrls: null}")
}
