@file:Suppress("ForbiddenImport")

package semantics.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ListEngineResult
import model.ResolverOccurrenceId
import model.UncompletedPromiseException
import model.VariableBinding
import model.testing.TestWorld
import model.testing.testRoot
import model.requireObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariableBindingsStateTest {
    @Test
    fun `bindings distinguish undeclared incomplete and bound to null`(): Unit =
        runBlocking {
            val state = VariableBindingsState()
            val variable = variableAt(emptyList())

            assertFalse(state.isBound(variable))
            assertFailsWith<IllegalStateException> { state.getBinding(variable) }

            state.declareBinding(variable)
            val fetched = async { state.fetchBinding(variable) }
            assertFalse(state.isBound(variable))
            assertFalse(fetched.isCompleted)
            assertFailsWith<UncompletedPromiseException> { state.getBinding(variable) }

            assertTrue(state.completeBinding(variable, null))
            assertTrue(state.isBound(variable))
            assertEquals(VariableBinding.of(null), state.getBinding(variable))
            assertEquals(VariableBinding.of(null), fetched.await())
            assertFalse(state.isBound(variableAt(listOf(ListEngineResult.Index.of(0)))))
        }

    @Test
    fun `declared bindings complete exactly once`() {
        val state = VariableBindingsState()
        val variable = variableAt(emptyList())

        state.declareBinding(variable)
        assertFailsWith<IllegalStateException> { state.declareBinding(variable) }
        assertTrue(state.completeBinding(variable, 1))
        assertFalse(state.completeBinding(variable, 2))
        assertFalse(state.cancelBinding(variable, CancellationException("late")))
        assertEquals(VariableBinding.of(1), state.getBinding(variable))
    }

    @Test
    fun `declared bindings cancel exactly once`(): Unit =
        runBlocking {
            val state = VariableBindingsState()
            val variable = variableAt(emptyList())
            val cancellation = CancellationException("binding producer cancelled")

            state.declareBinding(variable)
            assertTrue(state.cancelBinding(variable, cancellation))

            assertTrue(state.isBound(variable))
            assertEquals(
                cancellation.message,
                assertFailsWith<CancellationException> { state.getBinding(variable) }.message,
            )
            assertEquals(
                cancellation.message,
                assertFailsWith<CancellationException> { state.fetchBinding(variable) }.message,
            )
            assertFalse(state.cancelBinding(variable, cancellation))
            assertFalse(state.completeBinding(variable, 1))
        }

    @Test
    fun `bindings can be installed immediately exactly once`(): Unit =
        runBlocking {
            val state = VariableBindingsState()
            val variable = variableAt(emptyList())

            state.bindVariable(variable, 1)
            assertTrue(state.isBound(variable))
            assertEquals(VariableBinding.of(1), state.getBinding(variable))
            assertEquals(VariableBinding.of(1), state.fetchBinding(variable))
            assertFailsWith<IllegalStateException> { state.bindVariable(variable, 2) }
            assertFailsWith<IllegalStateException> { state.declareBinding(variable) }
        }

    @Test
    fun `immediate binding rejects a previously declared variable`() {
        val state = VariableBindingsState()
        val variable = variableAt(emptyList())
        state.declareBinding(variable)
        assertFailsWith<IllegalStateException> { state.bindVariable(variable, null) }
    }

    private fun variableAt(path: List<model.PathComponent>): model.VariableInstanceId {
        val schema = TestWorld.fromSDL("type Query { value(seed: Int): Int }").schema
        val field = schema.requireObjectField("Query", "value")
        return requireNotNull(
            Arguments.Variable.of(field, "seed")
                .instantiate(ResolverOccurrenceId.at(schema.testRoot(), path))
                .instanceId,
        )
    }
}
