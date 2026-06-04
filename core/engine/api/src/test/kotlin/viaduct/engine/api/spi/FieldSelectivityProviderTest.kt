package viaduct.engine.api.spi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate

class FieldSelectivityProviderTest {
    private val coordinate = Coordinate("TestType", "testField")

    @Test
    fun `Never returns false`() {
        assertFalse(FieldSelectivityProvider.Never.isSelective(coordinate))
    }

    @Test
    fun `Always returns true`() {
        assertTrue(FieldSelectivityProvider.Always.isSelective(coordinate))
    }

    @Test
    fun `or Always or Never returns true`() {
        assertTrue((FieldSelectivityProvider.Always or FieldSelectivityProvider.Never).isSelective(coordinate))
    }

    @Test
    fun `or Never or Always returns true`() {
        assertTrue((FieldSelectivityProvider.Never or FieldSelectivityProvider.Always).isSelective(coordinate))
    }

    @Test
    fun `or Never or Never returns false`() {
        assertFalse((FieldSelectivityProvider.Never or FieldSelectivityProvider.Never).isSelective(coordinate))
    }

    @Test
    fun `or Always or Always returns true`() {
        assertTrue((FieldSelectivityProvider.Always or FieldSelectivityProvider.Always).isSelective(coordinate))
    }
}
