package viaduct.api.reflect

import kotlin.reflect.KClass
import viaduct.api.types.GRT
import viaduct.apiannotations.StableApi

/**
 * A ReflectedType describes static properties of a GraphQL type
 */
@StableApi
interface Type<out T : GRT> {
    /** the GraphQL name of this type */
    val name: String

    /** the KClass that describes values for this type */
    val kcls: KClass<out T>

    companion object {
        /** Create a [Type] from the provided [KClass] */
        fun <T : GRT> ofClass(cls: KClass<T>): Type<T> =
            object : Type<T> {
                override val name: String = cls.simpleName!!
                override val kcls: KClass<out T> = cls

                override fun equals(other: Any?): Boolean {
                    if (other !is Type<*>) return false
                    return name == other.name && kcls == other.kcls
                }

                override fun hashCode(): Int {
                    var result = name.hashCode()
                    result = 31 * result + kcls.hashCode()
                    return result
                }
            }
    }
}
