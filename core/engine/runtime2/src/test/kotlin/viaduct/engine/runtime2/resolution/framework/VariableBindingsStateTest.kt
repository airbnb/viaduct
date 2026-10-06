@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.UncompletedPromiseException
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot

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

    private fun variableAt(path: List<viaduct.engine.runtime2.model.PathComponent>): viaduct.engine.runtime2.model.VariableInstanceId {
        val schema = TestWorld.fromSDL("type Query { value(seed: Int): Int }").schema
        val field = schema.requireObjectField("Query", "value")
        return requireNotNull(
            Arguments.Variable.of(field, "seed")
                .instantiate(ResolverOccurrenceId.at(schema.testRoot(), path))
                .instanceId,
        )
    }
}
