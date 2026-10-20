package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.ResultPath
import graphql.execution.SimpleDataFetcherExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.mocks.MockFlagManager

class ExecuteExecutionPlanTest {
    private val completer = FieldCompleter(SimpleDataFetcherExceptionHandler(), false)

    @ParameterizedTest
    @CsvSource("Obj, true", "Obj!, true", "Obj, false", "Obj!, false")
    fun `object completion applies enclosing field nullability`(
        objectType: String,
        incrementalExecutionEnabled: Boolean,
    ) = runTest {
        val base = mkObjectCompletionParameters(
            schemaSDL = "extend type Query { obj: $objectType } type Obj { a: Int, b: Int }",
            coordinate = "Query" to "obj",
            query = "{ obj { a ... @defer { b } } }",
        )
        val ctx = if (incrementalExecutionEnabled) {
            base
        } else {
            base.copy(
                _engineExecutionContext = ContextMocks(
                    myFullSchema = base.engineExecutionContext.fullSchema,
                    myFlagManager = MockFlagManager.Disabled,
                ).engineExecutionContext,
            )
        }
        val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
        setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
        setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))
        ctx.currentObjectEngineResult.fieldResolutionState.complete(Unit)

        val result = completer.completeObject(ctx).await()

        assertEquals(if (incrementalExecutionEnabled) mapOf("a" to 1) else mapOf("a" to 1, "b" to 2), result.value)
        assertEquals(objectType == "Obj", result.isNullableField)
        if (incrementalExecutionEnabled) {
            assertEquals(1, result.work.groups.size)
            assertEquals(mapOf("b" to 2), result.work.tasks.single().run().data)
        } else {
            assertEquals(Work.empty, result.work)
        }
    }

    @Test
    fun `object completion registers newly collected defers at the response path`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ aliasedObj: obj { ... @defer(label: \"A\") { broken: a } } }",
            )
            val usage = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).newDeferUsages.single()

            val deferMap = completeWithFieldFailure(ctx, "broken")

            val group = deferMap.getValue(usage)
            assertEquals(ResultPath.rootPath().segment("aliasedObj"), group.path)
            assertEquals("A", group.label)
            assertEquals(null, group.parent)
            assertTrue(ctx.deferMap.isEmpty())
        }

    @Test
    fun `object completion links new groups to inherited parents`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ ... @defer(label: \"outer\") { obj { ... @defer(label: \"inner\") { broken: a } } } }",
            )
            val parentUsage = checkNotNull(base.field).occurrences.single().deferUsage!!
            val parent = DeferDeliveryGroup(ResultPath.rootPath(), "outer", null)
            val ctx = base.copy(deferMap = mapOf(parentUsage to parent))
            val childUsage = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).newDeferUsages.single()

            val deferMap = completeWithFieldFailure(ctx, "broken")

            assertSame(parent, deferMap.getValue(parentUsage))
            assertSame(parent, deferMap.getValue(childUsage).parent)
            assertEquals(mapOf(parentUsage to parent), ctx.deferMap)
        }

    @Test
    fun `object completion preserves the defer map when incremental execution is disabled`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ ... @defer(label: \"outer\") { obj { ... @defer(label: \"inner\") { broken: a } } } }",
            )
            val parentUsage = checkNotNull(base.field).occurrences.single().deferUsage!!
            val inheritedDeferMap = mapOf(parentUsage to DeferDeliveryGroup(ResultPath.rootPath(), "outer", null))
            val ctx = base.copy(
                _engineExecutionContext = ContextMocks(
                    myFullSchema = base.engineExecutionContext.fullSchema,
                    myFlagManager = MockFlagManager.Disabled,
                ).engineExecutionContext,
                deferMap = inheritedDeferMap,
            )

            val deferMap = completeWithFieldFailure(ctx, "broken")

            assertSame(inheritedDeferMap, deferMap)
            assertSame(inheritedDeferMap, ctx.deferMap)
        }

    @Test
    fun `execution plans without deferred fields complete synchronously including empty plans`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(1)))

            for (selected in listOf(emptyMap(), fields)) {
                val result = completer.executeExecutionPlan(ctx, BuildExecutionPlan(selected))

                assertTrue(result is Value.Sync)
                assertEquals(if (selected.isEmpty()) emptyMap() else mapOf("first" to 1), result.getCompleted().data)
                assertEquals(Work.empty, result.getCompleted().work)
            }
        }

    @Test
    fun `object completion returns current fields without waiting for deferred groups`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int, c: Int }",
                coordinate = "Query" to "obj",
                query = """
                    {
                        obj {
                            ... @defer(label: "A") { first: a shared: c }
                            b
                            ... @defer(label: "B") { shared: c }
                        }
                    }
                """.trimIndent(),
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val deferred = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromDeferred(deferred))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))
            setRawFieldValue(ctx, fields.getValue("shared"), Value.fromValue(resolved(3)))

            val completion = completer.completeObject(ctx)
            assertTrue(completion.asDeferred().isCompleted)
            val result = completion.await()

            assertEquals(mapOf("b" to 2), result.value)
            assertEquals(setOf("A", "B"), result.work.groups.map { it.label }.toSet())
            val tasks = result.work.tasks.associateBy { it.deferredFragments.map { group -> group.label }.toSet() }
            assertEquals(2, tasks.size)
            assertEquals(mapOf("shared" to 3), tasks.getValue(setOf("A", "B")).run().data)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { tasks.getValue(setOf("A")).run() }
            assertFalse(pending.isCompleted)
            deferred.complete(resolved(1))
            assertEquals(mapOf("first" to 1), pending.await().data)
            assertFalse(ctx.currentObjectEngineResult.fieldResolutionState.isCompleted)
        }

    @Test
    fun `a deferred non-null failure stays separate from the initial result`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, required: Int! }",
                coordinate = "Query" to "obj",
                query = "{ obj { a ... @defer { broken: required } } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
            val deferred = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("broken"), Value.fromDeferred(deferred))

            val completion = completer.completeObject(ctx).await()
            assertEquals(mapOf("a" to 1), completion.value)
            deferred.complete(resolved(null))
            val result = completion.work.tasks.single().run()

            assertEquals(null, result.data)
            assertEquals(listOf(listOf("obj", "broken")), result.errors.map { it.path })
            assertTrue(ctx.errorAccumulator.isEmpty())
        }

    @Test
    fun `object completion waits for lazy object validity before returning fields or work`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a ... @defer { b } } }",
            )
            val oer = ObjectEngineResultImpl.newPendingForType(base.currentObjectEngineResult.type)
            val ctx = base.copy(currentObjectEngineResult = oer)
            val fields = FieldExecutionHelpers.collectFields(oer.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val completion = completer.completeObject(ctx)
            assertFalse(completion.asDeferred().isCompleted)
            oer.resolveToValue()
            val result = completion.await()

            assertEquals(mapOf("a" to 1), result.value)
            assertEquals(mapOf("b" to 2), result.work.tasks.single().run().data)
        }

    @Test
    fun `lazy objects resolved to null discard fields and deferred work`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a ... @defer { b } } }",
            )
            val oer = ObjectEngineResultImpl.newPendingForType(base.currentObjectEngineResult.type)
            val ctx = base.copy(currentObjectEngineResult = oer)
            val fields = FieldExecutionHelpers.collectFields(oer.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val completion = completer.completeObject(ctx)
            assertFalse(completion.asDeferred().isCompleted)
            oer.resolveToNull()
            val result = completion.await()

            assertTrue(result is FieldCompletionResult.NullResult)
            assertEquals(Work.empty, result.work)
            assertTrue(ctx.errorAccumulator.isEmpty())
        }

    @Test
    fun `plans preserve nested work and complete object fields in their enclosing defer context`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { child: Child, other: Child } type Child { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = """
                    {
                        obj {
                            child { a ... @defer(label: "child") { b } }
                            ... @defer(label: "outer") { other { a ... @defer(label: "inner") { b } } }
                        }
                    }
                """.trimIndent(),
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            for (responseName in listOf("child", "other")) {
                val field = fields.getValue(responseName)
                val oer = ObjectEngineResultImpl.newForType(ctx.graphQLSchema.getObjectType("Child"))
                val child = ctx.forField(ctx.currentObjectEngineResult.type, field).forObjectTraversal(field, oer, ctx.localContext, null)
                val subfields = FieldExecutionHelpers.collectFields(oer.type, child).collectedFieldsMap
                setRawFieldValue(child, subfields.getValue("a"), Value.fromValue(resolved(1)))
                setRawFieldValue(child, subfields.getValue("b"), Value.fromValue(resolved(2)))
                setRawFieldValue(ctx, field, Value.fromValue(resolved(oer)))
            }

            val result = completer.completeObject(ctx).await()

            assertEquals(mapOf("child" to mapOf("a" to 1)), result.value)
            assertEquals(setOf("outer", "child"), result.work.groups.map { it.label }.toSet())
            val tasks = result.work.tasks.associateBy { it.deferredFragments.single().label }
            assertEquals(mapOf("b" to 2), tasks.getValue("child").run().data)
            val outer = tasks.getValue("outer").run()
            assertEquals(mapOf("other" to mapOf("a" to 1)), outer.data)
            val inner = outer.work.groups.single()
            assertEquals("inner", inner.label)
            assertSame(tasks.getValue("outer").deferredFragments.single(), inner.parent)
            assertEquals(mapOf("b" to 2), outer.work.tasks.single().run().data)
        }

    /** Adapted from graphql-js src/execution/__tests__/defer-test.ts (7fdd84e). */
    @Nested
    inner class GraphQLJsTests {
        @Test
        fun `Can defer fragments containing scalar types`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        query HeroNameQuery {
                            hero {
                                id
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { name }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromValue(resolved("1")))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromValue(resolved("Luke")))
                val plan = BuildExecutionPlan(fields)
                assertEquals(1, plan.newCollectedFieldsMaps.size)

                val result = executePlan(ctx, plan).await()

                assertEquals(mapOf("id" to "1"), result.data)
                assertEquals(mapOf("name" to "Luke"), result.work.tasks.single().run().data)
            }

        @Test
        fun `Does not emit empty defer fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { name: String }",
                    coordinate = "Query" to "hero",
                    query = "{ hero { ... @defer { name @skip(if: true) } } }",
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap

                val result = executePlan(ctx, BuildExecutionPlan(fields))

                assertTrue(result is Value.Sync)
                assertEquals(emptyMap<String, Any?>(), result.getCompleted().data)
                assertEquals(Work.empty, result.getCompleted().work)
            }

        @Test
        fun `Emits children of empty defer fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { name: String }",
                    coordinate = "Query" to "hero",
                    query = "{ hero { ... @defer(label: \"outer\") { ... @defer(label: \"inner\") { name } } } }",
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromValue(resolved("Luke")))
                val plan = BuildExecutionPlan(fields)
                assertTrue(plan.collectedFieldsMap.isEmpty())
                assertEquals(1, plan.newCollectedFieldsMaps.size)

                val result = executePlan(ctx, plan).await()

                assertEquals(emptyMap<String, Any?>(), result.data)
                assertEquals("inner", result.work.groups.single().label)
                assertEquals("outer", result.work.groups.single().parent!!.label)
                assertEquals(mapOf("name" to "Luke"), result.work.tasks.single().run().data)
            }

        @Test
        fun `Can separately emit defer fragments with different labels with varying fields`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                ... @defer(label: "DeferID") { id }
                                ... @defer(label: "DeferName") { name }
                            }
                        }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                val id = CompletableDeferred<FieldResolutionResult>()
                val name = CompletableDeferred<FieldResolutionResult>()
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromDeferred(id))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromDeferred(name))
                val plan = BuildExecutionPlan(fields)
                assertTrue(plan.collectedFieldsMap.isEmpty())
                assertEquals(2, plan.newCollectedFieldsMaps.size)

                val result = executePlan(ctx, plan).await()
                assertEquals(emptyMap<String, Any?>(), result.data)
                val tasks = result.work.tasks.associateBy { it.deferredFragments.single().label }
                val nameCompletion = async(start = CoroutineStart.UNDISPATCHED) { tasks.getValue("DeferName").run() }
                name.complete(resolved("Luke"))
                assertEquals(mapOf("name" to "Luke"), nameCompletion.await().data)
                assertFalse(id.isCompleted)
                id.complete(resolved("1"))
                assertEquals(mapOf("id" to "1"), tasks.getValue("DeferID").run().data)
            }

        @Test
        fun `Handles errors thrown in deferred fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, name: String }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                id
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { name }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromValue(resolved("1")))
                setRawFieldValue(ctx, fields.getValue("name"), Value.fromThrowable(IllegalStateException("bad")))

                val result = executePlan(ctx, BuildExecutionPlan(fields)).await()

                assertEquals(mapOf("id" to "1"), result.data)
                val deferred = result.work.tasks.single().run()
                assertEquals(mapOf("name" to null), deferred.data)
                val error = deferred.errors.single()
                assertEquals(listOf("hero", "name"), error.path)
                assertTrue(error.message.contains("bad"))
                assertTrue(ctx.errorAccumulator.isEmpty())
            }

        @Test
        fun `Handles non-nullable errors thrown outside deferred fragments`() =
            runTest {
                val ctx = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { hero: Hero } type Hero { id: ID, nonNullName: String! }",
                    coordinate = "Query" to "hero",
                    query = """
                        {
                            hero {
                                nonNullName
                                ...NameFragment @defer
                            }
                        }
                        fragment NameFragment on Hero { id }
                    """.trimIndent(),
                )
                val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
                val deferred = CompletableDeferred<FieldResolutionResult>()
                setRawFieldValue(ctx, fields.getValue("nonNullName"), Value.fromValue(resolved(null)))
                setRawFieldValue(ctx, fields.getValue("id"), Value.fromDeferred(deferred))

                val completion = executePlan(ctx, BuildExecutionPlan(fields))

                assertTrue(completion.asDeferred().isCompleted)
                assertThrows<NonNullableFieldWasNullException> { completion.await() }
                assertEquals(listOf(listOf("hero", "nonNullName")), ctx.errorAccumulator.toList().map { it.path })
                deferred.complete(resolved("1"))
            }
    }

    private suspend fun completeWithFieldFailure(
        ctx: ExecutionParameters,
        responseKey: String,
    ): DeferMap {
        val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
        setRawFieldValue(ctx, fields.getValue(responseKey), Value.fromThrowable(IllegalStateException("failed")))
        ctx.currentObjectEngineResult.fieldResolutionState.complete(Unit)
        lateinit var deferMap: DeferMap
        val handler = DataFetcherExceptionHandler { parameters ->
            val executionParameters = parameters.dataFetchingEnvironment.engineExecutionContext.executionHandle as ExecutionParameters
            deferMap = executionParameters.deferMap
            SimpleDataFetcherExceptionHandler().handleException(parameters)
        }

        val fieldCompleter = FieldCompleter(handler, false)
        val completionCtx = ctx.copy(constants = ctx.constants.copy(fieldCompleter = fieldCompleter))
        val result = fieldCompleter.completeObject(completionCtx).await()

        if (result.work.tasks.isEmpty()) {
            assertEquals(mapOf(responseKey to null), result.value)
        } else {
            assertEquals(emptyMap<String, Any?>(), result.value)
            assertEquals(mapOf(responseKey to null), result.work.tasks.single().run().data)
        }
        return deferMap
    }

    private fun executePlan(
        ctx: ExecutionParameters,
        plan: ExecutionPlan,
    ): Value<ExecutionPlanResult> {
        val collected = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx)
        val completionCtx = ctx.withNewDeferMap(GetNewDeferMap(collected.newDeferUsages, ctx.path, ctx.deferMap))
        return completer.executeExecutionPlan(completionCtx, plan)
    }

    private fun resolved(value: Any?) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), value)
}
