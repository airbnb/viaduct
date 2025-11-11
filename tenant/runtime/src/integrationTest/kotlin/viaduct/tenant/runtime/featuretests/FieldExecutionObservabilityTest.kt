package viaduct.tenant.runtime.featuretests

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.context.FieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.observability.ExecutionObservabilityContext
import viaduct.engine.runtime.getLocalContextForType
import viaduct.tenant.runtime.featuretests.fixtures.Bar
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.featuretests.fixtures.get

@ExperimentalCoroutinesApi
class FieldExecutionObservabilityTest {
    @Test
    fun `test no operation name should not break`() {
        val instrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentation.asStandardInstrumentation())
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
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
                },
                resolverName = "query-string1-resolver"
            )
            .build()
            .execute("{string1}")
            .assertJson("{data: {string1: \"1\"}}")

        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals(setOf("RESOLVER:query-string1-resolver"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())
        assertEquals(setOf(null), instrumentation.getFieldRequiredBy("Query", "string1").toSet())

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "string1"),
                ("Query" to "idField")
            ),
            instrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
    }

    @Test
    fun `resolver name is passed to instrumentation`() {
        val instrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentation.asStandardInstrumentation())
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
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
                },
                resolverName = "query-string1-resolver"
            )
            .build()
            .execute("query testQuery {string1}")
            .assertJson("{data: {string1: \"1\"}}")

        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals(setOf("RESOLVER:query-string1-resolver"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())
        assertEquals(setOf("OPERATION:testQuery"), instrumentation.getFieldRequiredBy("Query", "string1").toSet())

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "string1"),
                ("Query" to "idField")
            ),
            instrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
        assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
    }

    @Test
    fun `test same field is fetched multiple times`() {
        val instrumentation = TestObservabilityInstrumentation()
        val childPlanExecuted = CountDownLatch(2)

        // fieldExecute and fieldComplete run in parallel, which creates a race condition where fieldComplete
        // may run before the child plan executes, potentially canceling the child plan.
        // This instrumentation will ensure the child plans are executed, and resolvers can wait for child plan execution before returning values.
        val countDownInstrumentation = createCountDownInstrumentation(
            "Query" to "idField",
            mapOf("RESOLVER:query-string1-resolver" to childPlanExecuted)
        )

        val instrumentations = ChainedModernGJInstrumentation(
            listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation.asStandardInstrumentation)
        )

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentations)
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
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
                    // Wait until the child plan is executed before returning values. Otherwise, the child plan may be cancelled.
                    // Wait for 1s as a safeguard to indefinite waiting.
                    childPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                },
                resolverName = "query-string1-resolver"
            )
            .build()
            .execute("query testQuery {first: string1, second: string1}")
            .assertJson("{data: {first: \"true\", second: \"true\"}}")

        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertTrue(instrumentation.getFieldRequiredBy("Query", "idField").toSet().contains("RESOLVER:query-string1-resolver")) {
            "Expected 'query-string1-resolver' to be in the required by set for 'idField'"
        }
        assertTrue((instrumentation.getFieldRequiredBy("Query", "idField").size == 2)) {
            "Expected 'idField' to be required by the resolver twice, but found: ${instrumentation.getFieldRequiredBy("Query", "idField")}"
        }

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "string1"),
                ("Query" to "idField")
            ),
            instrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1")) {
            "Expected 'query-string-resolver' but found ${instrumentation.getFieldResolvedBy("Query", "string1")}"
        }
        assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
    }

    @Test
    fun `test a field is queried by both the operation and the resolver`() {
        val instrumentation = TestObservabilityInstrumentation()
        val idFieldResolverChildPlanExecuted = CountDownLatch(1)
        val string1ResolverChildPlanExecuted = CountDownLatch(1)

        // fieldExecute and fieldComplete run in parallel, which creates a race condition where fieldComplete
        // may run before the child plan executes, potentially canceling the child plan.
        // This instrumentation will ensure the child plans are executed, and resolvers can wait for child plan execution before returning values.
        val countDownInstrumentation = createCountDownInstrumentation(
            "Query" to "idField",
            mapOf(
                "RESOLVER:query-string1-resolver" to string1ResolverChildPlanExecuted,
                "OPERATION:testQuery" to idFieldResolverChildPlanExecuted
            )
        )

        val instrumentations = ChainedModernGJInstrumentation(
            listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation.asStandardInstrumentation)
        )

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentations)
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
                // resolver returns a GRT value to the tenant runtime, which we expect to be unwrapped before
                // it's handed over to the engine
                idFieldResolverChildPlanExecuted.await(1, TimeUnit.SECONDS)
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
                    // Wait until the child plan is executed before returning values. Otherwise, the child plan may be cancelled.
                    // Wait for 1s as a safeguard to indefinite waiting.
                    string1ResolverChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                },
                resolverName = "query-string1-resolver"
            )
            .build()
            .execute("query testQuery {idField, string1}")
            .assertJson("{data: {idField: \"QmF6OjE=\", string1: \"true\"}}")

        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals(setOf("RESOLVER:query-string1-resolver", "OPERATION:testQuery"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())
        assertEquals(setOf("OPERATION:testQuery"), instrumentation.getFieldRequiredBy("Query", "string1").toSet())

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "string1"),
                ("Query" to "idField")
            ),
            instrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
        assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
    }

    @Test
    fun `test a field is queried by multiple resolvers`() {
        val instrumentation = TestObservabilityInstrumentation()
        val queryString1ResolverChildPlanExecuted = CountDownLatch(1)
        val queryString2ResolverChildPlanExecuted = CountDownLatch(1)

        // fieldExecute and fieldComplete run in parallel, which creates a race condition where fieldComplete
        // may run before the child plan executes, potentially canceling the child plan.
        // This instrumentation will ensure the child plans are executed, and resolvers can wait for child plan execution before returning values.
        val countDownInstrumentation = createCountDownInstrumentation(
            "Query" to "idField",
            mapOf(
                "RESOLVER:query-string1-resolver" to queryString1ResolverChildPlanExecuted,
                "RESOLVER:query-string2-resolver" to queryString2ResolverChildPlanExecuted
            )
        )

        val instrumentations = ChainedModernGJInstrumentation(
            listOf(instrumentation.asStandardInstrumentation, countDownInstrumentation.asStandardInstrumentation)
        )

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentations)
            .resolver("Query" to "idField", resolverName = "query-id-field-resolver") { ctx ->
                // resolver returns a GRT value to the tenant runtime, which we expect to be unwrapped before
                // it's handed over to the engine
                ctx.globalIDFor(Baz.Reflection, "1")
            }
            .resolver(
                "Query" to "string1",
                objectValueFragment = "idField",
                resolveFn = { ctx: UntypedFieldContext ->
                    val idFieldValue = ctx.objectValue.get<GlobalID<Baz>>("idField")
                    // Wait until the child plan is executed before returning values. Otherwise, the child plan may be cancelled.
                    // Wait for 1s as a safeguard to indefinite waiting.
                    queryString1ResolverChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                },
                resolverName = "query-string1-resolver"
            )
            .resolver(
                "Query" to "string2",
                objectValueFragment = "idField",
                resolveFn = { ctx: UntypedFieldContext ->
                    val idFieldValue = ctx.objectValue.get<GlobalID<Baz>>("idField")
                    // Wait until the child plan is executed before returning values. Otherwise, the child plan may be cancelled.
                    // Wait for 1s as a safeguard to indefinite waiting.
                    queryString2ResolverChildPlanExecuted.await(1, TimeUnit.SECONDS).toString()
                },
                resolverName = "query-string2-resolver"
            )
            .build()
            .execute("query testQuery {string1, string2}")
            .assertJson("{data: {string1: \"true\", string2: \"true\"}}")

        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
                ("Query" to "string2")
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals(setOf("RESOLVER:query-string1-resolver", "RESOLVER:query-string2-resolver"), instrumentation.getFieldRequiredBy("Query", "idField").toSet())

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "idField"),
                ("Query" to "string1"),
                ("Query" to "string2")
            ),
            instrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
        assertEquals("query-string2-resolver", instrumentation.getFieldResolvedBy("Query", "string2"))
        assertEquals("query-id-field-resolver", instrumentation.getFieldResolvedBy("Query", "idField"))
    }

    @Test
    fun `test a field is queried by multiple resolvers with different args`() {
        val instrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentation.asStandardInstrumentation())
            .resolver(
                "Query" to "hasArgs2",
                resolveFn = { it.arguments.get<String>("x") + "_resolved" },
                resolverName = "query-has-args1-resolver"
            )
            .resolver(
                "Query" to "string1",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<String>("hasArgs2") },
                "hasArgs2(x:\"string1\")",
                resolverName = "query-string1-resolver"
            )
            .resolver(
                "Query" to "string2",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<String>("hasArgs2") },
                "hasArgs2(x:\"string2\")",
                resolverName = "query-string2-resolver"
            )
            .build()
            .execute("query testQuery {string1, string2}")
            .assertJson("{data: {string1: \"string1_resolved\", string2: \"string2_resolved\"}}")

        assertEquals(
            setOf(
                ("Query" to "hasArgs2"),
                ("Query" to "string1"),
                ("Query" to "string2"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals(setOf("RESOLVER:query-string1-resolver", "RESOLVER:query-string2-resolver"), instrumentation.getFieldRequiredBy("Query", "hasArgs2").toSet())

        assertEquals(
            setOf(
                ("Query" to "hasArgs2"),
                ("Query" to "string1"),
                ("Query" to "string2"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals("query-string1-resolver", instrumentation.getFieldResolvedBy("Query", "string1"))
        assertEquals("query-has-args1-resolver", instrumentation.getFieldResolvedBy("Query", "hasArgs2"))
        assertEquals("query-string2-resolver", instrumentation.getFieldResolvedBy("Query", "string2"))
    }

    @Test
    fun `test a field is queried by same resolver multiple times`() {
        val instrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = instrumentation.asStandardInstrumentation())
            .resolver(
                "Query" to "hasArgs1",
                resolveFn = { it.arguments.get<Int>("x") * 2 },
                resolverName = "query-has-args1-resolver"
            )
            .resolver(
                "Query" to "hasArgs3",
                resolveFn = { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("hasArgs1") * 3 },
                "hasArgs1(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
                resolverName = "query-has-args3-resolver"
            )
            .build()
            .execute("query testQuery {first: hasArgs3(x: 1), second: hasArgs3(x: 2)}")
            .assertJson("{data: {first: 6, second: 12}}")

        assertEquals(
            setOf(
                ("Query" to "hasArgs1"),
                ("Query" to "hasArgs3"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertTrue(instrumentation.getFieldRequiredBy("Query", "hasArgs1").toSet().contains("RESOLVER:query-has-args3-resolver")) {
            "Expected 'query-has-args3-resolver' to be in the required by set for 'hasArgs1'"
        }
        assertTrue(instrumentation.getFieldRequiredBy("Query", "hasArgs1").size == 2) {
            "Expected 'hasArgs1' to be required by the same resolver twice, but found: ${instrumentation.getFieldRequiredBy("Query", "hasArgs1")}"
        }

        assertEquals(
            setOf(
                ("Query" to "hasArgs1"),
                ("Query" to "hasArgs3"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertEquals("query-has-args1-resolver", instrumentation.getFieldResolvedBy("Query", "hasArgs1"))
        assertEquals("query-has-args3-resolver", instrumentation.getFieldResolvedBy("Query", "hasArgs3"))
    }

    @Test
    fun `test variable resolvers`() {
        val instrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z:Int }", useFakeGRTs = true, instrumentation = instrumentation.asStandardInstrumentation())
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.queryValue.get<Int>("y") * 5 },
                queryValueFragment = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z")),
                resolverName = "query-x-resolver"
            )
            .resolver("Query" to "y", resolverName = "query-y-resolver") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z", resolverName = "query-z-resolver") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "query testQuery{x}")

        assertEquals(
            setOf(
                ("Query" to "x"),
                ("Query" to "y"),
                ("Query" to "z"),
            ),
            instrumentation.fieldToRequiredByLookup.keys
        )

        assertTrue(instrumentation.getFieldRequiredBy("Query", "x").toSet().contains("OPERATION:testQuery")) {
            "Expected 'OPERATION:testQuery' to be in the required by set for 'Query.x' but found: ${instrumentation.getFieldRequiredBy("Query", "x")}"
        }
        assertTrue(instrumentation.getFieldRequiredBy("Query", "y").toSet().contains("RESOLVER:query-x-resolver")) {
            "Expected 'RESOLVER:query-x-resolver' to be in the required by set for 'Query.y' but found: ${instrumentation.getFieldRequiredBy("Query", "y")}"
        }
        assertTrue(instrumentation.getFieldRequiredBy("Query", "z").toSet().contains("VARIABLES_RESOLVER:RESOLVER:query-x-resolver")) {
            "Expected 'VARIABLE_RESOLVER:RESOLVER:query-x-resolver' to be in the required by set for 'Query.z' but found: ${instrumentation.getFieldRequiredBy("Query", "z")}"
        }
    }

    @Test
    fun `test resolved_by tag in nested fields`() {
        val resolvedByInstrumentation = TestObservabilityInstrumentation()

        FeatureTestBuilder(FeatureTestSchemaFixture.sdl, instrumentation = resolvedByInstrumentation.asStandardInstrumentation)
            .resolver(
                "Query" to "string1",
                { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    "Query.string1=[${ctx.objectValue.getFoo()?.getValue()}]"
                },
                "foo { value, valueWithoutResolver }",
                resolverName = "query-string1-resolver"
            )
            .resolver("Query" to "foo", resolverName = "query-foo-resolver") { Foo.Builder(it).valueWithoutResolver("valueWithoutResolver").build() }
            .resolver(
                "Foo" to "value",
                { ctx: FieldExecutionContext<Foo, Query, Arguments.NoArguments, CompositeOutput.NotComposite> ->
                    "Foo.value=[${ctx.objectValue.getBar()?.getValue()}]"
                },
                "bar { value }",
                resolverName = "foo-value-resolver"
            )
            .resolver("Foo" to "bar", resolverName = "foo-bar-resolver") { Bar.Builder(it).build() }
            .resolver("Bar" to "value", resolverName = "bar-value-resolver") { "Bar.value=[VALUE]" }
            .build()
            .assertJson(
                "{data: {string1: \"Query.string1=[Foo.value=[Bar.value=[VALUE]]]\", foo: {valueWithoutResolver: \"valueWithoutResolver\"}}}",
                "{string1, foo {valueWithoutResolver}}",
            )

        // verify the beginFieldFetch instrumentation was called with the expected field coordinates and resolvedBy values
        assertEquals(
            setOf(
                ("Query" to "string1"),
                ("Query" to "foo"),
                ("Foo" to "value"),
                ("Foo" to "valueWithoutResolver"),
                ("Foo" to "bar"),
                ("Bar" to "value")
            ),
            resolvedByInstrumentation.fieldToResolvedByLookup.keys
        )

        assertEquals("query-string1-resolver", resolvedByInstrumentation.getFieldResolvedBy("Query", "string1"))
        assertEquals("query-foo-resolver", resolvedByInstrumentation.getFieldResolvedBy("Query", "foo"))
        assertEquals("foo-value-resolver", resolvedByInstrumentation.getFieldResolvedBy("Foo", "value"))
        assertEquals("query-foo-resolver", resolvedByInstrumentation.getFieldResolvedBy("Foo", "valueWithoutResolver"))
        assertEquals("foo-bar-resolver", resolvedByInstrumentation.getFieldResolvedBy("Foo", "bar"))
        assertEquals("bar-value-resolver", resolvedByInstrumentation.getFieldResolvedBy("Bar", "value"))
    }

    private fun createCountDownInstrumentation(
        coordinate: Coordinate,
        attributionToLatchMap: Map<String?, CountDownLatch>
    ): ViaductInstrumentationBase {
        return object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldExecution {
            override fun beginFieldExecution(
                parameters: InstrumentationFieldParameters,
                state: InstrumentationState?
            ): InstrumentationContext<Any>? {
                val typeName = parameters.executionStepInfo.objectType.name
                val fieldName = parameters.executionStepInfo.field.name
                if (coordinate == (typeName to fieldName)) {
                    val attribution = parameters.executionContext.getLocalContextForType<ExecutionObservabilityContext>()?.attribution?.toTagString()
                    attributionToLatchMap[attribution]?.countDown()
                }
                return SimpleInstrumentationContext.noOp()
            }
        }
    }

    class TestObservabilityInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFieldExecution,
        IViaductInstrumentation.WithBeginFieldFetch {
        val fieldToRequiredByLookup = ConcurrentHashMap<Coordinate, CopyOnWriteArrayList<String?>>()
        val fieldToResolvedByLookup = ConcurrentHashMap<Coordinate, String>()

        override fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            val typeName = parameters.executionStepInfo.objectType.name
            val fieldName = parameters.executionStepInfo.field.name
            val coordinate = (typeName to fieldName)

            val attribution = parameters.executionContext.getLocalContextForType<ExecutionObservabilityContext>()?.attribution
            fieldToRequiredByLookup.computeIfAbsent(coordinate) { CopyOnWriteArrayList() }
                .add(attribution?.toTagString())

            return SimpleInstrumentationContext.noOp()
        }

        fun getFieldRequiredBy(
            typeName: String,
            fieldName: String
        ): List<String?> {
            return fieldToRequiredByLookup[(typeName to fieldName)] ?: emptyList()
        }

        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            val typeName = parameters.executionStepInfo.objectType.name
            val fieldName = parameters.executionStepInfo.field.name
            val coordinate = (typeName to fieldName)

            val resolver = parameters.environment.getLocalContextForType<ExecutionObservabilityContext>()?.resolverMetadata?.name
            resolver?.let {
                fieldToResolvedByLookup.put(coordinate, it)
            }

            return SimpleInstrumentationContext.noOp()
        }

        fun getFieldResolvedBy(
            typeName: String,
            fieldName: String
        ): String? {
            return fieldToResolvedByLookup[(typeName to fieldName)]
        }
    }
}
