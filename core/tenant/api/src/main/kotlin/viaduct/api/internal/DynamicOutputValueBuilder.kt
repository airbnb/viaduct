package viaduct.api.internal

import viaduct.apiannotations.InternalApi

/**
 * Builder interface for dynamic output values.
 *
 * This is not meant to be used directly, but rather through [ViaductObjectBuilder.dynamicBuilderFor].
 */
@InternalApi
interface DynamicOutputValueBuilder<T> {
    fun put(
        name: String,
        value: Any?
    ): DynamicOutputValueBuilder<T>

    fun build(): T
}
