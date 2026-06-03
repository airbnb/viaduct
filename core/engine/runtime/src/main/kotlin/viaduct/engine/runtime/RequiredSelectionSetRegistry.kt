package viaduct.engine.runtime

import viaduct.engine.api.RequiredSelectionSet

interface RequiredSelectionSetRegistry {
    /**
     * Get a list of [RequiredSelectionSet] for a provided typeName-fieldName coordinate.
     * If the coordinate has no RequiredSelectionSet, it'll be an empty list.
     */
    fun getRequiredSelectionSetsForField(
        typeName: String,
        fieldName: String
    ): List<RequiredSelectionSet> {
        return getFieldResolverRequiredSelectionSets(typeName, fieldName) +
            getFieldCheckerRequiredSelectionSets(typeName, fieldName)
    }

    fun getFieldResolverRequiredSelectionSets(
        typeName: String,
        fieldName: String,
    ): List<RequiredSelectionSet>

    fun getFieldCheckerRequiredSelectionSets(
        typeName: String,
        fieldName: String
    ): List<RequiredSelectionSet>

    /**
     * Get a list of [RequiredSelectionSet] for the provided typeName.
     * If the type has no RequiredSelectionSet, it'll be an empty list.
     */
    fun getRequiredSelectionSetsForType(typeName: String): List<RequiredSelectionSet> = getTypeCheckerRequiredSelectionSets(typeName)

    fun getTypeCheckerRequiredSelectionSets(typeName: String): List<RequiredSelectionSet>

    /** A [RequiredSelectionSetRegistry] that returns empty list for every request */
    object Empty : RequiredSelectionSetRegistry {
        override fun getRequiredSelectionSetsForField(
            typeName: String,
            fieldName: String
        ): List<RequiredSelectionSet> = emptyList()

        override fun getFieldResolverRequiredSelectionSets(
            typeName: String,
            fieldName: String,
        ): List<RequiredSelectionSet> = emptyList()

        override fun getFieldCheckerRequiredSelectionSets(
            typeName: String,
            fieldName: String
        ): List<RequiredSelectionSet> = emptyList()

        override fun getRequiredSelectionSetsForType(typeName: String): List<RequiredSelectionSet> = emptyList()

        override fun getTypeCheckerRequiredSelectionSets(typeName: String): List<RequiredSelectionSet> = emptyList()
    }
}
