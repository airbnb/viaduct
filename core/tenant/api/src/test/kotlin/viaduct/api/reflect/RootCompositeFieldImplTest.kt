package viaduct.api.reflect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.internal.RootCompositeFieldImpl
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT

class RootCompositeFieldImplTest {
    private class Foo : GRT

    private val foo = Type.ofClass(Foo::class)

    private class Bar : CompositeOutput

    private val bar = Type.ofClass(Bar::class)

    @Test
    fun `properties are set correctly`() {
        val f = RootCompositeFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar)
        assertEquals("field", f.name)
        assertEquals(foo, f.containingType)
        assertEquals(bar, f.type)
    }

    @Test
    fun `is a subtype of CompositeField`() {
        val f: CompositeField<Foo, Bar> = RootCompositeFieldImpl<Foo, Bar, Arguments.NoArguments>("field", foo, bar)
        assertEquals("field", f.name)
    }

    @Test
    fun `is a subtype of RootCompositeField`() {
        val f: RootCompositeField<Foo, Bar, Arguments.NoArguments> = RootCompositeFieldImpl("field", foo, bar)
        assertEquals("field", f.name)
    }
}
