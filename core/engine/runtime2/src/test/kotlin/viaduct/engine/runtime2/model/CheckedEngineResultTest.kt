@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.testing.TestWorld

class CheckedEngineResultTest {
    @Test
    fun `cell completion includes every cell slot`() {
        val cell = newCell()

        assertFalse(cell.isCompleted)
        cell.value.set("ready")
        assertFalse(cell.isCompleted)

        cell.fieldCheckerResult.complete(null)
        assertTrue(cell.isCompleted)
    }

    @Test
    fun `unfinished value or checker slots produce pending attempts`() {
        val valuePending = newCell()
        valuePending.value
        assertSame(EngineResultIsPending, valuePending.materializeCheckedValue { true })

        val fieldPending = newCell()
        fieldPending.value.set("field")
        fieldPending.fieldCheckerResult
        assertSame(EngineResultIsPending, fieldPending.materializeCheckedValue { true })

        val (typePending, _) = newObjectCell(Promise.ofDeferred())
        typePending.fieldCheckerResult.complete(null)
        assertSame(EngineResultIsPending, typePending.materializeCheckedValue { true })
    }

    @Test
    fun `null checker results leave values open including null`() {
        val valueCell = newCell()
        valueCell.value.set("open")
        valueCell.fieldCheckerResult.complete(null)
        assertSame("open", valueCell.materializeCheckedValue { true })

        val nullCell = newCell()
        nullCell.value.set(null)
        nullCell.fieldCheckerResult.complete(null)
        assertNull(nullCell.materializeCheckedValue { true })
    }

    @Test
    fun `completed denial short circuits an unfinished value slot`() {
        val denial = TestCheckerError("denied")
        val cell = newCell()
        cell.value
        cell.setActivated(true)
        assertTrue(cell.fieldCheckerResult.complete(denial))

        val materialized = cell.materializeCheckedValue { true }

        assertSame(denial.error, (materialized as ErrorEngineResult).errorData.cause)
    }

    @Test
    fun `awaiting checked value checks denial before the raw value`() =
        runBlocking {
            val denial = TestCheckerError("asynchronous denial")
            val cell = newCell()
            cell.value
            val checkerPromise = cell.fieldCheckerResult
            cell.setActivated(true)
            val awaiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    cell.awaitCheckedValue { true }
                }

            assertFalse(awaiting.isCompleted)
            checkerPromise.complete(denial)
            val result = withTimeout(1_000) { awaiting.await() }

            assertSame(denial.error, (result as ErrorEngineResult).errorData.cause)
            assertFalse(cell.value.isCompleted)
        }

    @Test
    fun `unfinished checker slot takes precedence over a raw error`() {
        val rawError = ErrorEngineResult.of(EngineErrorData.of(IllegalStateException("raw")))
        val cell = newCell()
        cell.value.set(rawError)
        val fieldPromise = cell.fieldCheckerResult

        assertFalse(cell.isCompleted)
        assertSame(EngineResultIsPending, cell.materializeCheckedValue { true })

        fieldPromise.complete(CheckerResult.Success)
        assertSame(rawError, cell.materializeCheckedValue { true })
    }

    @Test
    fun `type and field errors combine before consumer applicability`() {
        val fieldError = TestCheckerError("field")
        val combinedError = TestCheckerError("combined")
        val typeError = CombiningCheckerError(fieldError, combinedError)
        val (cell, value) = newObjectCell(Promise.of(typeError))
        assertTrue(cell.fieldCheckerResult.complete(fieldError))
        var observedError: CheckerResult.Error? = null

        val denied =
            cell.materializeCheckedValue { error ->
                observedError = error
                true
            }

        assertSame(combinedError, observedError)
        assertSame(combinedError.error, (denied as ErrorEngineResult).errorData.cause)
        assertSame(value, cell.materializeCheckedValue { false })
    }

    private fun newObjectCell(typeCheckerResult: Promise<CheckerResult?> = Promise.of(null)): Pair<EngineResultCell, ObjectEngineResult> {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query { value: Value }
                    type Value { text: String }
                    """.trimIndent(),
                ).schema
        val valueType = schema.requireType("Value")
        require(valueType is viaduct.graphql.schema.ViaductSchema.Object)
        val value =
            ObjectEngineResult.of(
                type = valueType,
                typeCheckerResult = typeCheckerResult,
                mutable = true,
            )
        val cell =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(
                    ObjectEngineResult.GroundKey.of(
                        schema.requireObjectField("Query", "value"),
                        emptyMap(),
                    ),
                )
        cell.value.set(value)
        return cell to value
    }

    private fun newCell(): EngineResultCell {
        val schema = TestWorld.fromSDL("type Query { value: String }").schema
        return ObjectEngineResult
            .of(schema.requireQueryTypeDef(), mutable = true)
            .reserveCell(
                ObjectEngineResult.GroundKey.of(
                    schema.requireObjectField("Query", "value"),
                    emptyMap(),
                ),
            )
    }
}

private open class TestCheckerError(message: String) : CheckerResult.Error {
    override val error: Exception = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

private class CombiningCheckerError(
    private val expectedFieldError: CheckerResult.Error,
    private val combinedError: CheckerResult.Error,
) : TestCheckerError("type") {
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        assertSame(expectedFieldError, fieldResult)
        return combinedError
    }
}
