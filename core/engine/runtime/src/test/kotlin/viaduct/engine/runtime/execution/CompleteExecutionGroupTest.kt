package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.ResultPath
import graphql.schema.GraphQLNonNull
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException

class CompleteExecutionGroupTest {
    @Test
    fun `empty group returns empty data errors and work`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            )
            val parentError = GraphQLError.newError().message("parent").path(listOf("obj", "a")).build()
            ctx.errorAccumulator.add(parentError)

            val result = CompleteExecutionGroup(ctx, emptyMap())

            assertEquals(emptyMap<String, Any?>(), result.data)
            result.errors.shouldBeEmpty()
            assertEquals(Work.empty, result.work)
            ctx.errorAccumulator.toList().shouldContainExactly(parentError)
        }

    @Test
    fun `waits for the supplied fields and preserves aliases and order`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { second: b first: a ignored: a } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val first = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromDeferred(first))
            setRawFieldValue(ctx, fields.getValue("second"), Value.fromValue(resolved(2)))

            val pending = async(start = CoroutineStart.UNDISPATCHED) { CompleteExecutionGroup(ctx, fields - "ignored") }
            assertFalse(pending.isCompleted)
            first.complete(resolved(1))
            val result = pending.await()

            assertEquals(mapOf("second" to 2, "first" to 1), result.data)
            result.data!!.keys.toList().shouldContainExactly("second", "first")
            result.errors.shouldBeEmpty()
            assertEquals(Work.empty, result.work)
        }

    @Test
    fun `nullable errors preserve sibling data and stay separate from parent and other groups`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val parentError = GraphQLError.newError().message("parent").path(listOf("obj", "first")).build()
            val groupError = GraphQLError.newError().message("group").path(listOf("obj", "first")).build()
            ctx.errorAccumulator.add(parentError)
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(FieldResolutionResult.fromErrors(listOf(groupError))))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val failedFieldGroup = CompleteExecutionGroup(ctx, fields)
            val otherGroup = CompleteExecutionGroup(ctx, fields.filterKeys { it == "b" })

            assertEquals(mapOf("first" to null, "b" to 2), failedFieldGroup.data)
            failedFieldGroup.errors.shouldContainExactly(groupError)
            assertEquals(Work.empty, failedFieldGroup.work)
            assertEquals(mapOf("b" to 2), otherGroup.data)
            otherGroup.errors.shouldBeEmpty()
            ctx.errorAccumulator.toList().shouldContainExactly(parentError)
        }

    @Test
    fun `non-null field failure nulls only its group`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { required: Int!, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: required b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(null)))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val failed = CompleteExecutionGroup(ctx, fields.filterKeys { it == "first" })
            val sibling = CompleteExecutionGroup(ctx, fields.filterKeys { it == "b" })

            assertNull(failed.data)
            assertEquals(listOf(listOf("obj", "first")), failed.errors.map { it.path })
            assertEquals(Work.empty, failed.work)
            assertEquals(mapOf("b" to 2), sibling.data)
            sibling.errors.shouldBeEmpty()
            ctx.errorAccumulator.toList().shouldBeEmpty()
        }

    @Test
    fun `asynchronous non-null failure returns group errors without leaking to the parent`() =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { required: Int! }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: required } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val fieldError = GraphQLError.newError().message("failed").path(listOf("obj", "first")).build()
            val completion = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromDeferred(completion))

            val pending = async(start = CoroutineStart.UNDISPATCHED) { CompleteExecutionGroup(ctx, fields) }
            assertFalse(pending.isCompleted)
            completion.complete(FieldResolutionResult.fromErrors(listOf(fieldError)))
            val result = pending.await()

            assertNull(result.data)
            result.errors.shouldContainExactly(fieldError)
            assertEquals(Work.empty, result.work)
            ctx.errorAccumulator.toList().shouldBeEmpty()
        }

    @Nested
    inner class BuildResultTests {
        private val fieldError = GraphQLError.newError().message("failed").path(listOf("a")).build()

        @Test
        fun `successful completion preserves data errors and nested work without running it`() {
            val group = DeferDeliveryGroup(ResultPath.rootPath().segment("obj"), "child", null)
            val task = ExecutionGroupTask(setOf(group), group.path) { error("Result conversion must not run deferred work") }
            val work = Work(setOf(group), listOf(task))
            val data = mapOf("a" to null, "obj" to emptyMap<String, Any?>())

            val result = CompleteExecutionGroup.buildResult(Result.success(ExecutionPlanResult(data, work)), listOf(fieldError))

            assertEquals(data, result.data)
            result.errors.shouldContainExactly(fieldError)
            assertEquals(work, result.work)
        }

        @Test
        fun `non-null failure returns no data or work`() {
            val result = CompleteExecutionGroup.buildResult(Result.failure(nonNullFailure()), listOf(fieldError))

            assertNull(result.data)
            result.errors.shouldContainExactly(fieldError)
            assertEquals(Work.empty, result.work)
        }

        @Test
        fun `field completion failure returns no data or work`() {
            val failure = FieldCompletionException(listOf(fieldError))

            val result = CompleteExecutionGroup.buildResult(Result.failure(failure), listOf(fieldError))

            assertNull(result.data)
            result.errors.shouldContainExactly(fieldError)
            assertEquals(Work.empty, result.work)
        }

        @Test
        fun `expected completion failures without recorded errors are rethrown`() {
            val failures = listOf(nonNullFailure(), FieldCompletionException(listOf(fieldError)))

            for (failure in failures) {
                val thrown = assertThrows<RuntimeException> {
                    CompleteExecutionGroup.buildResult(Result.failure(failure), emptyList())
                }
                assertSame(failure, thrown)
            }
        }

        @Test
        fun `unexpected failure is rethrown even with recorded errors`() {
            val failure = IllegalStateException("engine failure")

            val thrown = assertThrows<IllegalStateException> {
                CompleteExecutionGroup.buildResult(Result.failure(failure), listOf(fieldError))
            }

            assertSame(failure, thrown)
        }

        @Test
        fun `cancellation is rethrown even with recorded errors`() {
            val cancellation = CancellationException("cancelled")

            val thrown = assertThrows<CancellationException> {
                CompleteExecutionGroup.buildResult(Result.failure(cancellation), listOf(fieldError))
            }

            assertSame(cancellation, thrown)
        }

        private fun nonNullFailure(): NonNullableFieldWasNullException {
            val path = ResultPath.rootPath().segment("a")
            val stepInfo = ExecutionStepInfo.newExecutionStepInfo()
                .type(GraphQLNonNull.nonNull(Scalars.GraphQLInt))
                .path(path)
                .build()
            return NonNullableFieldWasNullException(stepInfo, path)
        }
    }

    private fun resolved(value: Any?) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), value)
}
