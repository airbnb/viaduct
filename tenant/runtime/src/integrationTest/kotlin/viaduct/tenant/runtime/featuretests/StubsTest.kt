package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.mocks.MockInternalContext
import viaduct.engine.api.mocks.MockSchema
import viaduct.tenant.runtime.featuretests.fixtures.ArgumentsStub
import viaduct.tenant.runtime.featuretests.fixtures.ObjectStub

@ExperimentalCoroutinesApi
class ObjectStubTest {
    private fun mk(
        sdl: String,
        vararg values: Pair<String, Any?>,
        type: String = "Query",
    ): ObjectStub =
        MockSchema.mk(sdl).let { schema ->
            ObjectStub(
                MockInternalContext(schema),
                @Suppress("DEPRECATION")
                viaduct.engine.api.mocks.MockEngineObjectData(schema.schema.getObjectType(type), values.toMap())
            )
        }

    @Test
    fun `get`(): Unit =
        kotlinx.coroutines.runBlocking {
            // missing
            mk("extend type Query { x: Int }").apply {
                assertNull(get<Int?>("x"))
            }

            // explicit null
            mk("extend type Query { x: Int }", "x" to null).apply {
                assertNull(get<Int?>("x"))
            }

            // scalar
            mk("extend type Query { x: Int }", "x" to 1).apply {
                assertEquals(1, get<Int>("x"))
            }

            // aliased scalar
            mk("extend type Query { x: Int }", "myx" to 1).apply {
                assertEquals(1, get<Int>("x", "myx"))
            }

            // list
            mk("extend type Query { x: [Int] }", "x" to listOf(1)).apply {
                assertThrows<IllegalArgumentException> { get<List<Int>>("x") }
                assertEquals(listOf(1), get<List<Int>>("x", Int::class))
            }
        }
}

class ArgumentsStubTest {
    private fun mk(vararg pairs: Pair<String, Any?>): ArgumentsStub = ArgumentsStub(pairs.toMap())

    @Test
    fun `get and tryGet`() {
        // empty
        mk().apply {
            assertThrows<NullPointerException> { get<Int>("x") }
            assertNull(tryGet<Int>("x"))
        }

        // explicit null
        mk("x" to null).apply {
            assertThrows<NullPointerException> { get<Int>("x") }
            assertNull(tryGet<Int>("x"))
        }

        // value
        mk("x" to 1).apply {
            assertEquals(1, get("x"))
            assertEquals(1, tryGet("x"))
        }
    }
}
