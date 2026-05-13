package viaduct.api.types

import kotlin.Enum as KotlinEnum
import kotlin.reflect.KClass
import viaduct.apiannotations.StableApi

/**
 * Tagging interface for GraphQL enum types.
 *
 * Codegen-produced Kotlin enums implement this interface so the framework can identify and
 * handle them uniformly. Use [Companion.enumFrom] to look up an enum constant by its
 * GraphQL string value when deserializing resolver arguments.
 */
@StableApi
interface Enum : GRT {
    @StableApi
    companion object {
        /**
         * Looks up the enum constant of [clazz] whose name equals [value].
         *
         * @throws NoSuchElementException if no constant with that name exists.
         */
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
