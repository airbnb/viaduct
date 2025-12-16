package viaduct.api.internal

import kotlin.reflect.KClass
import viaduct.api.reflect.Type
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

@StableApi
interface ReflectionLoader {
    /**
     * Return a Type describing the reflected type information for the type with the provided name.
     * Only defined for [Object] and [Input] types (i.e., instantiable, concrete types from the actual schema).
     * If no such Type information exists, a [MissingReflection] will be thrown.
     */
    fun reflectionFor(name: String): Type<*>

    /**
     * Load a GRT class by name directly, without requiring a $Reflection inner class.
     * This is useful for Arguments classes which don't have reflection metadata.
     * Only defined for [Object] and [InputLike] types (i.e., instantiable, concrete types).
     * If the class cannot be found, a ClassNotFoundException will be thrown.
     */
    fun getGRTKClassFor(name: String): KClass<*>
}

@InternalApi
class MissingReflection(val name: String, val reason: String, cause: Throwable? = null) : Exception(cause) {
    override val message = "Missing reflection for type $name: $reason"
}
