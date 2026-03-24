package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.MissingReflection
import viaduct.api.reflect.Type
import viaduct.api.types.GRT
import viaduct.codegen.utils.JavaName

class Data {
    class Value {
        class Builder
    }
}

class DataV2 {
    class Builder
}

class Type_Field_Arguments

class User : GRT {
    object Reflection : Type<User> {
        override val name = "Valid"
        override val kcls: KClass<out User> = User::class
    }
}

class ClassResolverTest {
    private fun assertMissings(resolver: ClassResolver) {
        assertThrows<ClassNotFoundException> { resolver.valueClassFor("Missing") }
        assertThrows<ClassNotFoundException> { resolver.argumentsClassFor("Missing", "missing") }
        assertThrows<ClassNotFoundException> { resolver.builderClassFor("Missing") }
        assertThrows<ClassNotFoundException> { resolver.mainClassFor("Missing") }
        assertThrows<ClassNotFoundException> { resolver.v2BuilderClassFor("Missing") }
        assertThrows<MissingReflection> { resolver.reflectionFor("Missing") }
    }

    @Test
    fun fromMaps() {
        class Value

        class Arguments

        class Builder

        class Data {
            inner class Builder
        }

        val resolver = ClassResolver.fromMaps(
            valueClasses = mapOf("Value" to Value::class),
            argumentClasses = mapOf("Type" to "field" to Arguments::class),
            builderClasses = mapOf(
                "Builder" to Builder::class,
                "V2Builder" to DataV2.Builder::class
            ),
            dataClasses = mapOf("Data" to Data::class),
            reflections = mapOf("User" to User.Reflection)
        )

        assertEquals(Value::class.java, resolver.valueClassFor("Value"))
        assertEquals(Arguments::class.java, resolver.argumentsClassFor("Type", "field"))
        assertEquals(Builder::class.java, resolver.builderClassFor("Builder"))
        assertEquals(Data::class.java, resolver.mainClassFor("Data"))
        assertEquals(DataV2.Builder::class.java, resolver.v2BuilderClassFor("V2Builder"))
        assertEquals(User::class.java, resolver.reflectionFor("User").kcls.java)

        assertMissings(resolver)
    }

    @Test
    fun fromClassLoader() {
        val resolver = ClassResolver.fromClassLoader(
            JavaName("viaduct.tenant.codegen.bytecode.exercise"),
            ClassLoader.getSystemClassLoader()
        )

        assertEquals(Data::class.java, resolver.mainClassFor("Data"))
        assertEquals(Data.Value::class.java, resolver.valueClassFor("Data"))
        assertEquals(Data.Value.Builder::class.java, resolver.builderClassFor("Data"))
        assertEquals(Type_Field_Arguments::class.java, resolver.argumentsClassFor("Type", "field"))
        assertEquals(DataV2.Builder::class.java, resolver.v2BuilderClassFor("DataV2"))
        assertEquals(User::class.java, resolver.reflectionFor("User").kcls.java)

        assertMissings(resolver)
    }
}
