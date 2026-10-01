@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld

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
        val worldFixture = TestWorld.fromSDL("type Query { value: Int }")
        val world = worldFixture.assumptions
        return ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
    }
}
