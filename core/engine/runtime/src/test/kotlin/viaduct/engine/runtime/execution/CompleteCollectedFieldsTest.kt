package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.SimpleDataFetcherExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.runtime.EngineExecutionContextExtensions.incrementalExecutionEnabled
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException

class CompleteCollectedFieldsTest {
    private val completer = FieldCompleter(SimpleDataFetcherExceptionHandler(), false)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `null lazy objects do not wait for discarded field population`(resolveBeforeCompletion: Boolean): Unit =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            )
            val oer = ObjectEngineResultImpl.newPendingForType(base.currentObjectEngineResult.type)
            val ctx = base.copy(currentObjectEngineResult = oer)
            if (resolveBeforeCompletion) oer.resolveToNull()

            val completion = completer.completeObject(ctx)
            if (!resolveBeforeCompletion) {
                assertFalse(completion.asDeferred().isCompleted)
                oer.resolveToNull()
            }

            try {
                assertTrue(completion.asDeferred().isCompleted)
                assertTrue(completion.await() is FieldCompletionResult.NullResult)
                assertTrue(ctx.errorAccumulator.isEmpty())
                assertFalse(oer.fieldResolutionState.isCompleted)
            } finally {
                oer.fieldResolutionState.complete(Unit)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed lazy objects do not wait for discarded field population`(resolveBeforeCompletion: Boolean): Unit =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            )
            val oer = ObjectEngineResultImpl.newPendingForType(base.currentObjectEngineResult.type)
            val ctx = base.copy(currentObjectEngineResult = oer)
            val failure = IllegalStateException("lazy object failed")
            if (resolveBeforeCompletion) oer.resolveExceptionally(failure)

            val completion = completer.completeObject(ctx)
            if (!resolveBeforeCompletion) {
                assertFalse(completion.asDeferred().isCompleted)
                oer.resolveExceptionally(failure)
            }

            try {
                assertTrue(completion.asDeferred().isCompleted)
                val thrown = assertThrows<FieldCompletionException> { completion.await() }
                assertSame(failure, thrown.cause)
                assertEquals(listOf(listOf("obj")), ctx.errorAccumulator.toList().map { it.path })
                assertFalse(oer.fieldResolutionState.isCompleted)
            } finally {
                oer.fieldResolutionState.complete(Unit)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `successful lazy objects wait for validity and field population`(fieldsReadyFirst: Boolean): Unit =
        runTest {
            val base = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            )
            val oer = ObjectEngineResultImpl.newPendingForType(base.currentObjectEngineResult.type)
            val ctx = base.copy(currentObjectEngineResult = oer)
            val fields = FieldExecutionHelpers.collectFields(oer.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))
            val completion = completer.completeObject(ctx)

            if (fieldsReadyFirst) oer.fieldResolutionState.complete(Unit) else oer.resolveToValue()
            assertFalse(completion.asDeferred().isCompleted)
            if (fieldsReadyFirst) oer.resolveToValue() else oer.fieldResolutionState.complete(Unit)

            assertEquals(mapOf("a" to 1), completion.await().value)
        }

    @Test
    fun `nondeferred object completion waits for field population before reading its fields`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int, unselected: Int! }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a b } }",
            )
            assertTrue(ctx.engineExecutionContext.incrementalExecutionEnabled)
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(1)))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val pending = completer.completeObject(ctx)

            assertFalse(pending.asDeferred().isCompleted)
            ctx.currentObjectEngineResult.fieldResolutionState.complete(Unit)
            assertEquals(mapOf("first" to 1, "b" to 2), pending.await().value)
        }

    @Test
    fun `deferred object fields do not block the initial completion on object population`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a ... @defer(label: \"later\") { b } } }",
            )
            assertTrue(ctx.engineExecutionContext.incrementalExecutionEnabled)
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))

            val initial = completer.completeObject(ctx).await()

            assertEquals(mapOf("a" to 1), initial.value)
            assertFalse(ctx.currentObjectEngineResult.fieldResolutionState.isCompleted)
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))
            val deferred = initial.work.tasks.single().run()
            assertEquals(mapOf("b" to 2), deferred.data)
        }

    @Test
    fun `root completion does not wait on a population barrier for a different selection`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { a } }",
            ).copy(executionOrigin = ExecutionOrigin.Root)
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("a"), Value.fromValue(resolved(1)))

            assertEquals(mapOf("a" to 1), completer.completeObject(ctx).await().value)
            assertFalse(ctx.currentObjectEngineResult.fieldResolutionState.isCompleted)
        }

    @Test
    fun `empty and subset maps do not wait for unrelated fields`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(1)))

            val empty = completer.completeCollectedFields(ctx, emptyMap())
            val subset = completer.completeCollectedFields(ctx, fields.filterKeys { it == "first" })

            assertTrue(empty is Value.Sync)
            assertEquals(emptyMap<String, Any?>(), empty.getCompleted().data)
            assertEquals(Work.empty, empty.getCompleted().work)
            assertTrue(subset is Value.Sync)
            assertEquals(mapOf("first" to 1), subset.getCompleted().data)
            assertEquals(Work.empty, subset.getCompleted().work)
        }

    @Test
    fun `asynchronous fields retain collection order and aliases`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a second: b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val first = CompletableDeferred<FieldResolutionResult>()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromDeferred(first))
            setRawFieldValue(ctx, fields.getValue("second"), Value.fromValue(resolved(2)))

            val pending = completer.completeCollectedFields(ctx, fields)
            assertFalse(pending.asDeferred().isCompleted)
            first.complete(resolved(1))
            val result = pending.await()

            assertEquals(listOf("first", "second"), result.data.keys.toList())
            assertEquals(mapOf("first" to 1, "second" to 2), result.data)
            assertEquals(Work.empty, result.work)
        }

    @Test
    fun `nullable field errors keep sibling data`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { a: Int, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: a b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            val fieldError = GraphQLError.newError().message("failed").path(listOf("obj", "first")).build()
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(FieldResolutionResult.fromErrors(listOf(fieldError))))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            val result = completer.completeCollectedFields(ctx, fields).await()

            assertEquals(mapOf("first" to null, "b" to 2), result.data)
            assertEquals(Work.empty, result.work)
            assertEquals(listOf(fieldError), ctx.errorAccumulator.toList())
        }

    @Test
    fun `non-null failure propagates out of collected fields`(): Unit =
        runTest {
            val ctx = mkObjectCompletionParameters(
                schemaSDL = "extend type Query { obj: Obj } type Obj { required: Int!, b: Int }",
                coordinate = "Query" to "obj",
                query = "{ obj { first: required b } }",
            )
            val fields = FieldExecutionHelpers.collectFields(ctx.currentObjectEngineResult.type, ctx).collectedFieldsMap
            setRawFieldValue(ctx, fields.getValue("first"), Value.fromValue(resolved(null)))
            setRawFieldValue(ctx, fields.getValue("b"), Value.fromValue(resolved(2)))

            assertThrows<NonNullableFieldWasNullException> {
                completer.completeCollectedFields(ctx, fields).await()
            }
            assertEquals(listOf(listOf("obj", "first")), ctx.errorAccumulator.toList().map { it.path })
        }

    private fun resolved(value: Any?) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), value)
}
