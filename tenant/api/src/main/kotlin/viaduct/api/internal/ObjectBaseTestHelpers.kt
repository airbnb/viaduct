package viaduct.api.internal

/**
 * Utility class for testing purposes, used to expose otherwise-internal methods to test code.
 */
object ObjectBaseTestHelpers {
    /**
     * Similar to [ObjectBase.Builder.put], but allows setting an alias for the field.
     * This is primarily for testing purposes to ensure that the aliasing works correctly.
     */
    fun <T, R : ObjectBase.Builder<T>> putWithAlias(
        builder: R,
        name: String,
        alias: String,
        value: Any?
    ): R {
        builder.put(name, value, alias)
        return builder
    }
}
