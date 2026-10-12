package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ResultPath
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.dfe.engineExecutionContext

class CollectExecutionGroupsTest {
    @Test
    fun `empty map creates no tasks`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            )

            CollectExecutionGroups(ctx, emptyMap()).shouldBeEmpty()
        }

    @Test
    fun `tasks complete only their fields when run and preserve aliases and paths`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ aliasedObj: obj { ignored: a ... @defer(label: \"A\") { first: a second: b } } }",
            )
            val collected = FieldExecutionHelpers.collectFields(base.currentObjectEngineResult.type, base)
            val instrumentation = RecordingInstrumentation()
            val ctx = base.copy(
                constants = base.constants.copy(executionContext = base.executionContext.transform { it.instrumentation(instrumentation) }),
                deferMap = GetNewDeferMap(collected.newDeferUsages, base.path, base.deferMap),
            )
            val first = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("first"), Value.fromDeferred(first))
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("second"), Value.fromValue(resolved(2)))

            val task = CollectExecutionGroups(ctx, BuildExecutionPlan(collected.collectedFieldsMap).newCollectedFieldsMaps).single()

            instrumentation.fieldCompletionContexts.shouldBeEmpty()
            assertEquals(ResultPath.rootPath().segment("aliasedObj"), task.path)
            assertSame(ctx.deferMap.values.single(), task.deferredFragments.single())
            val pending = async(start = CoroutineStart.UNDISPATCHED) { task.run() }
            assertFalse(pending.isCompleted)
            first.complete(resolved(1))
            val result = pending.await()

            assertEquals(mapOf("first" to 1, "second" to 2), result.data)
            result.data!!.keys.toList().shouldContainExactly("first", "second")
            result.errors.shouldBeEmpty()
            assertEquals(Work.empty, result.work)
            ctx.deferUsageSet.shouldBeEmpty()
            ctx.errorAccumulator.toList().shouldBeEmpty()
        }

    @Test
    fun `overlapping defers share one task for their common fields`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int, c: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { ... @defer(label: \"A\") { a c } ... @defer(label: \"B\") { b c } } }",
            )
            val collected = FieldExecutionHelpers.collectFields(base.currentObjectEngineResult.type, base)
            val instrumentation = RecordingInstrumentation()
            val ctx = base.copy(
                constants = base.constants.copy(executionContext = base.executionContext.transform { it.instrumentation(instrumentation) }),
                deferMap = GetNewDeferMap(collected.newDeferUsages, base.path, base.deferMap),
            )
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("a"), Value.fromValue(resolved(1)))
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("b"), Value.fromValue(resolved(2)))
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("c"), Value.fromValue(resolved(3)))

            val tasks = CollectExecutionGroups(ctx, BuildExecutionPlan(collected.collectedFieldsMap).newCollectedFieldsMaps)

            assertEquals(3, tasks.size)
            val byGroups = tasks.associateBy { it.deferredFragments.map { group -> group.label }.toSet() }
            byGroups.keys.shouldContainExactlyInAnyOrder(setOf("A"), setOf("B"), setOf("A", "B"))
            assertEquals(ctx.deferMap.values.toSet(), byGroups.getValue(setOf("A", "B")).deferredFragments)
            assertEquals(mapOf("a" to 1), byGroups.getValue(setOf("A")).run().data)
            assertEquals(mapOf("b" to 2), byGroups.getValue(setOf("B")).run().data)
            assertEquals(mapOf("c" to 3), byGroups.getValue(setOf("A", "B")).run().data)
            instrumentation.fieldCompletionContexts.map {
                (it.parameters as InstrumentationFieldCompleteParameters).executionStepInfo.path
            }.shouldContainExactlyInAnyOrder(ctx.path.segment("a"), ctx.path.segment("b"), ctx.path.segment("c"))
        }

    @Test
    fun `task failures do not affect parent errors or sibling tasks`() =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { required: Int!, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { ... @defer(label: \"A\") { first: required } ... @defer(label: \"B\") { b } } }",
            )
            val collected = FieldExecutionHelpers.collectFields(base.currentObjectEngineResult.type, base)
            lateinit var failureCtx: ExecutionParameters
            val handler = DataFetcherExceptionHandler { parameters ->
                failureCtx = parameters.dataFetchingEnvironment.engineExecutionContext.executionHandle as ExecutionParameters
                SimpleDataFetcherExceptionHandler().handleException(parameters)
            }
            val ctx = base.copy(
                constants = base.constants.copy(fieldCompleter = FieldCompleter(handler, false)),
                deferMap = GetNewDeferMap(collected.newDeferUsages, base.path, base.deferMap),
            )
            val parentError = GraphQLError.newError().message("parent").path(listOf("obj", "first")).build()
            ctx.errorAccumulator.add(parentError)
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("first"), Value.fromThrowable(IllegalStateException("field")))
            setRawFieldValue(ctx, collected.collectedFieldsMap.getValue("b"), Value.fromValue(resolved(2)))
            val tasks = CollectExecutionGroups(ctx, BuildExecutionPlan(collected.collectedFieldsMap).newCollectedFieldsMaps)
                .associateBy { it.deferredFragments.single().label }

            val failed = tasks.getValue("A").run()
            val sibling = tasks.getValue("B").run()

            assertNull(failed.data)
            failed.errors.map { it.path }.shouldContainExactly(listOf(listOf("obj", "first")))
            assertEquals(Work.empty, failed.work)
            assertEquals(mapOf("b" to 2), sibling.data)
            sibling.errors.shouldBeEmpty()
            assertEquals(setOf(collected.newDeferUsages.single { it.defer.label == "A" }), failureCtx.deferUsageSet)
            assertSame(ctx.deferMap, failureCtx.deferMap)
            ctx.deferUsageSet.shouldBeEmpty()
            ctx.errorAccumulator.toList().shouldContainExactly(parentError)
        }

    @Test
    fun `missing delivery groups fail when collecting tasks`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { ... @defer(label: \"A\") { a } } }",
            )
            val collected = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx)
            val plan = BuildExecutionPlan(collected.collectedFieldsMap)

            assertThrows<IllegalStateException> { CollectExecutionGroups(ctx, plan.newCollectedFieldsMaps) }
        }

    private fun resolved(value: Any?) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), value)
}
