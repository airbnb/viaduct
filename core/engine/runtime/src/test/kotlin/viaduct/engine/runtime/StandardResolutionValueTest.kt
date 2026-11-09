package viaduct.engine.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.StandardResolutionValue

class StandardResolutionValueTest {
    @Test
    fun `StandardResolutionValue throws when wrapped multiple times`() {
        val inner = StandardResolutionValue(null)
        assertThrows<IllegalArgumentException> {
            StandardResolutionValue(inner)
        }
    }
}
