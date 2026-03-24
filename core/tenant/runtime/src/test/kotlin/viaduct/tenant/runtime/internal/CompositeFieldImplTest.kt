package viaduct.tenant.runtime.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.internal.CompositeFieldImpl
import viaduct.api.reflect.Type
import viaduct.api.types.GRT

class CompositeFieldImplTest {
    private class Foo : GRT

    private val foo = Type.ofClass(Foo::class)

    private class Bar : GRT

    private val bar = Type.ofClass(Bar::class)

    @Test
    fun simple() {
        val f = CompositeFieldImpl("field", foo, bar)
        assertEquals("field", f.name)
        assertEquals(foo, f.containingType)
        assertEquals(bar, f.type)
    }
}
