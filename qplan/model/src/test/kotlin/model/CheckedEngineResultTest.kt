@file:Suppress("ForbiddenImport")

package model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.testing.TestWorld
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

class CheckedEngineResultTest {
    @Test
    fun `cell completion includes every claimed cell slot`() {
        val cell = newCell()

        assertFalse(cell.isCompleted)
        cell.setValue("ready")
        assertTrue(cell.isCompleted)

        val fieldPromise = cell.createFieldCheckerResultPromise()
        assertFalse(cell.isCompleted)

        fieldPromise.complete(CheckerResult.Success)
        assertTrue(cell.isCompleted)
    }

    @Test
    fun `unfinished value or checker slots produce pending attempts`() {
        val valuePending = newCell()
        valuePending.reserveValue()
        assertSame(EngineResultIsPending, valuePending.materializeCheckedValue { true })

        val fieldPending = newCell()
        fieldPending.setValue("field")
        fieldPending.createFieldCheckerResultPromise()
        assertSame(EngineResultIsPending, fieldPending.materializeCheckedValue { true })

        val (typePending, pendingObject) = newObjectCell()
        pendingObject.createTypeCheckerResultPromise()
        assertSame(EngineResultIsPending, typePending.materializeCheckedValue { true })
    }

    @Test
    fun `unclaimed checker slots default open including for null`() {
        val valueCell = newCell()
        valueCell.setValue("open")
        assertSame("open", valueCell.materializeCheckedValue { true })

        val nullCell = newCell()
        nullCell.setValue(null)
        assertNull(nullCell.materializeCheckedValue { true })
    }

    @Test
    fun `completed denial short circuits an unfinished value slot`() {
        val denial = TestCheckerError("denied")
        val cell = newCell()
        cell.reserveValue()
        cell.setFieldCheckerResult(denial)

        val materialized = cell.materializeCheckedValue { true }

        assertSame(denial.error, (materialized as ErrorEngineResult).errorData.cause)
    }

    @Test
    fun `awaiting checked value checks denial before the raw value`() =
        runBlocking {
            val denial = TestCheckerError("asynchronous denial")
            val cell = newCell()
            cell.reserveValue()
            val checkerPromise = cell.createFieldCheckerResultPromise()
            cell.setActivated(true)
            val awaiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    cell.awaitCheckedValue { true }
                }

            assertFalse(awaiting.isCompleted)
            checkerPromise.complete(denial)
            val result = withTimeout(1_000) { awaiting.await() }

            assertSame(denial.error, (result as ErrorEngineResult).errorData.cause)
            assertFalse(cell.getValue().isCompleted)
        }

    @Test
    fun `unfinished checker slot takes precedence over a raw error`() {
        val rawError = ErrorEngineResult.of(EngineErrorData.of(IllegalStateException("raw")))
        val cell = newCell()
        cell.setValue(rawError)
        val fieldPromise = cell.createFieldCheckerResultPromise()

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
        val (cell, value) = newObjectCell()
        cell.setFieldCheckerResult(fieldError)
        value.setTypeCheckerResult(typeError)
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

    private fun newObjectCell(): Pair<EngineResultCell, ObjectEngineResult> {
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
        val value = ObjectEngineResult.of(valueType, mutable = true)
        val cell =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(
                    ObjectEngineResult.GroundKey.of(
                        schema.requireObjectField("Query", "value"),
                        emptyMap(),
                    ),
                )
        cell.setValue(value)
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
