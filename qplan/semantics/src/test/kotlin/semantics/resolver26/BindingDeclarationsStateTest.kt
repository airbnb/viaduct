@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.ObjectEngineResult
import model.requireQueryTypeDef
import model.testing.TestWorld

class BindingDeclarationsStateTest {
    @Test
    fun `await suspends until the object binding domain is declared`(): Unit =
        runBlocking {
            val state = BindingDeclarationsState()
            val target = target()

            val readiness = async { state.awaitBindingsDeclared(target) }
            assertFalse(readiness.isCompleted)

            state.markBindingsDeclared(target)
            readiness.await()
        }

    @Test
    fun `an object binding domain is marked exactly once`() {
        val state = BindingDeclarationsState()
        val target = target()

        state.markBindingsDeclared(target)
        assertFailsWith<IllegalStateException> { state.markBindingsDeclared(target) }
        runBlocking { state.awaitBindingsDeclared(target) }
    }

    private fun target(): ObjectEngineResult {
        val world = TestWorld.fromSDL("type Query { value: Int }").assumptions
        return ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
    }
}
