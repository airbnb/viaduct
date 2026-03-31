package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.errors.UnsetFieldException

class UnsetFieldExceptionTest {
    private val obj = MockSchema.mk("extend type Query { x: Int }").schema.queryType

    @Test
    fun `message -- field`() {
        assertTrue(
            UnsetFieldException("x", obj).message?.contains("field Query.x") == true
        )
    }

    @Test
    fun `message -- selection`() {
        assertTrue(
            UnsetFieldException("y", obj).message?.contains("aliased field y") == true
        )
    }

    @Test
    fun `message -- details`() {
        assertTrue(
            UnsetFieldException("x", obj, "DETAILS").message?.contains("DETAILS") == true
        )
    }
}
