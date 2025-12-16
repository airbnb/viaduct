package viaduct.api.types

import kotlin.Enum as KotlinEnum
import kotlin.reflect.KClass
import viaduct.apiannotations.StableApi

/**
 * Tagging interface for enum types
 */
@StableApi
interface Enum : GRT {
    companion object {
        fun <T> enumFrom(
            clazz: KClass<T>,
            value: String
        ): T where T : KotlinEnum<T>, T : Enum {
            // return a new instance of the enum type, which is clazz type and with value as its name
            try {
                return java.lang.Enum.valueOf(clazz.java, value)
            } catch (e: Exception) {
                throw NoSuchElementException("No enum constant ${clazz.simpleName}.$value")
            }
        }
    }
}
