package viaduct.engine.api

import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema

internal class NoOpCheckerExecutorFactoryImplTest {
    private val testSubject = NoOpCheckerExecutorFactoryImpl()
    private val mockSchema = MockSchema.mk("type AnyType { anyField: String }")

    @Test
    fun `Test no op functionality`() {
        val provider = testSubject.checkerExecutorForField(mockSchema, "anyType", "anyField")
        assertNull(provider)
    }

    @Test
    fun `Test no op on node`() {
        val provider = testSubject.checkerExecutorForType(mockSchema, "anyType")
        assertNull(provider)
    }
}
