@file:Suppress("unused")

package viaduct.tenant.runtime.internal

import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.MissingReflection
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.tenant.runtime.packageName

class ReflectionLoaderImplTest {
    private val mirror: ReflectionLoader = ReflectionLoaderImpl { name -> Class.forName("${this::class.packageName}.$name").kotlin }

    @Test
    fun `reflectionFor -- simple`() {
        Assertions.assertSame(Valid.Reflection, mirror.reflectionFor("Valid"))
    }

    @Test
    fun `reflectionFor -- errors`() {
        // class not defined
        assertThrows<MissingReflection> {
            mirror.reflectionFor("UndefinedClass")
        }

        // no Reflection
        assertThrows<MissingReflection> {
            mirror.reflectionFor("Invalid1")
        }

        // Reflection is not object
        assertThrows<MissingReflection> {
            mirror.reflectionFor("Invalid2")
        }

        // Reflection is not Type
        assertThrows<MissingReflection> {
            mirror.reflectionFor("Invalid3")
        }
    }
}

private class Valid : GRT {
    object Reflection : Type<Valid> {
        override val name = "Valid"
        override val kcls: KClass<out Valid> = Valid::class
    }
}

private class Invalid1 : GRT

private class Invalid2 : GRT {
    class Reflection : Type<Invalid2> {
        override val name = "Invalid2"
        override val kcls: KClass<out Invalid2> = Invalid2::class
    }
}

private class Invalid3 : GRT {
    object Reflection {
        @Suppress("ConstPropertyName")
        const val name = "Invalid2"
        val kcls: KClass<out Invalid3> = Invalid3::class
    }
}
