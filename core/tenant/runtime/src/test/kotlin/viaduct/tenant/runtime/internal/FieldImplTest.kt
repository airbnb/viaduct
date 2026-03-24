package viaduct.tenant.runtime.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.internal.FieldImpl
import viaduct.api.reflect.Type
import viaduct.api.types.GRT

class FieldImplTest {
    private class Foo : GRT

    private val foo = Type.ofClass(Foo::class)

    @Test
    fun simple() {
        val f = FieldImpl("field", foo)
        assertEquals("field", f.name)
        assertEquals(foo, f.containingType)
    }
}
