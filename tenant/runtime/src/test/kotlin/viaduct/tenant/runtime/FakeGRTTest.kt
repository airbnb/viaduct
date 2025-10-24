package viaduct.tenant.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductTenantUsageException
import viaduct.api.mocks.MockInternalContext
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkEngineObjectData

@ExperimentalCoroutinesApi
class FakeObjectTest {
    private fun mk(
        sdl: String,
        vararg values: Pair<String, Any?>,
        type: String = "Query",
    ): FakeObject =
        MockSchema.mk(sdl).let { schema ->
            FakeObject(
                MockInternalContext(schema),
                mkEngineObjectData(schema.schema.getObjectType(type), values.toMap())
            )
        }

    @Test
    fun `get`(): Unit =
        kotlinx.coroutines.runBlocking {
            // missing - should throw exception with mkEngineObjectData
            mk("extend type Query { x: Int }").apply {
                assertThrows<ViaductTenantUsageException> { get<Int?>("x") }
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
            mk("extend type Query { x: Int }", "x" to 1).apply {
                assertEquals(1, get<Int>("x", "x"))
            }

            // list
            mk("extend type Query { x: [Int] }", "x" to listOf(1)).apply {
                assertThrows<IllegalArgumentException> { get<List<Int>>("x") }
                assertEquals(listOf(1), get<List<Int>>("x", Int::class))
            }
        }
}

class FakeArgumentsTest {
    private fun mk(vararg pairs: Pair<String, Any?>): FakeArguments = FakeArguments(inputData = pairs.toMap())

    @Test
    fun `get and tryGet`() {
        // empty
        mk().apply {
            assertThrows<IllegalArgumentException> { get<Int>("x") }
            assertNull(tryGet<Int>("x"))
        }

        // explicit null
        mk("x" to null).apply {
            assertThrows<IllegalArgumentException> { get<Int>("x") }
            assertNull(tryGet<Int>("x"))
        }

        // value
        mk("x" to 1).apply {
            assertEquals(1, get("x"))
            assertEquals(1, tryGet("x"))
        }
    }
}
