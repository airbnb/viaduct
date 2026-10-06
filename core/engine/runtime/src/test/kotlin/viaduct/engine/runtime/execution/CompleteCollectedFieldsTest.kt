package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.SimpleDataFetcherExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext

class CompleteCollectedFieldsTest {
    private val completer = FieldCompleter(SimpleDataFetcherExceptionHandler(), false)

    @Test
    fun `empty and subset maps do not wait for unrelated fields`() =
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
            assertEquals(emptyMap<String, Any?>(), empty.getCompleted())
            assertTrue(subset is Value.Sync)
            assertEquals(mapOf("first" to 1), subset.getCompleted())
        }

    @Test
    fun `asynchronous fields retain collection order and aliases`() =
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

            assertEquals(listOf("first", "second"), result.keys.toList())
            assertEquals(mapOf("first" to 1, "second" to 2), result)
        }

    @Test
    fun `nullable field errors keep sibling data`() =
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

            assertEquals(mapOf("first" to null, "b" to 2), result)
            assertEquals(listOf(fieldError), ctx.errorAccumulator.toList())
        }

    @Test
    fun `non-null failure propagates out of collected fields`() =
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
