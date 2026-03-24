package viaduct.api.reflect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.types.CompositeOutput

class TypeTest {
    @Test
    fun fromClass() {
        class Foo : CompositeOutput
        val type = Type.ofClass(Foo::class)
        assertEquals("Foo", type.name)
        assertEquals(Foo::class, type.kcls)
        assertEquals(Type.ofClass(Foo::class).hashCode(), Type.ofClass(Foo::class).hashCode())
    }
}
