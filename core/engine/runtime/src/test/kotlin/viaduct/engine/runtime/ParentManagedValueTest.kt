package viaduct.engine.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.StandardResolutionValue

class ParentManagedValueTest {
    @Test
    fun `ParentManagedValue throws when wrapped multiple times`() {
        val inner = ParentManagedValue(null)
        assertThrows<IllegalArgumentException> {
            ParentManagedValue(inner)
        }
    }

    @Test
    fun `ParentManagedValue throws when wrapping a StandardResolutionValue`() {
        val inner = StandardResolutionValue("x")
        assertThrows<IllegalArgumentException> {
            ParentManagedValue(inner)
        }
    }
}
