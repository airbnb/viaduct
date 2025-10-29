package viaduct.engine.api

interface FieldResolverDispatcherRegistry {
    /**
     * Get a [FieldResolverDispatcher] for a provided typeName-fieldName coordinate,
     * or null if no FieldResolverDispatcher is registered for the given coordinate.
     */
    fun getFieldResolverDispatcher(
        typeName: String,
        fieldName: String
    ): FieldResolverDispatcher?

    object Empty : FieldResolverDispatcherRegistry {
        override fun getFieldResolverDispatcher(
            typeName: String,
            fieldName: String
        ): FieldResolverDispatcher? = null
    }
}
