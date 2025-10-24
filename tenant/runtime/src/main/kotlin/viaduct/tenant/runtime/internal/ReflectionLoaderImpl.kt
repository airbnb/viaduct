package viaduct.tenant.runtime.internal

import kotlin.reflect.KClass
import viaduct.api.internal.MissingReflection
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type

class ReflectionLoaderImpl(private val grtClassforName: (String) -> KClass<*>) : ReflectionLoader {
    override fun reflectionFor(name: String): Type<*> {
        val cls = try {
            grtClassforName("$name\$Reflection")
        } catch (e: ClassNotFoundException) {
            throw MissingReflection(name, "class not found", e)
        }
        val obj = cls.objectInstance
            ?: throw MissingReflection(name, "Reflection is not a kotlin object")
        if (obj !is Type<*>) {
            throw MissingReflection(name, "Reflection is not a Type")
        }
        return obj
    }

    override fun getGRTKClassFor(name: String): KClass<*> {
        return grtClassforName(name)
    }
}
