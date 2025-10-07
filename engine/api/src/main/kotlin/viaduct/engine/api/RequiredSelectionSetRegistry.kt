package viaduct.engine.api

interface RequiredSelectionSetRegistry {
    /**
     * Get a list of [RequiredSelectionSet] for a provided typeName-fieldName coordinate.
     * If the coordinate has no RequiredSelectionSet, it'll be an empty list.
     */
    fun getRequiredSelectionSetsForField(
        typeName: String,
        fieldName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> {
        return getFieldResolverRequiredSelectionSets(typeName, fieldName) +
            getFieldCheckerRequiredSelectionSets(typeName, fieldName, executeAccessChecksInModstrat)
    }

    fun getFieldResolverRequiredSelectionSets(
        typeName: String,
        fieldName: String,
    ): List<RequiredSelectionSet>

    fun getFieldCheckerRequiredSelectionSets(
        typeName: String,
        fieldName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet>

    /**
     * Get a list of [RequiredSelectionSet] for the provided typeName.
     * If the type has no RequiredSelectionSet, it'll be an empty list.
     */
    fun getRequiredSelectionSetsForType(
        typeName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> = getTypeCheckerRequiredSelectionSets(typeName, executeAccessChecksInModstrat)

    fun getTypeCheckerRequiredSelectionSets(
        typeName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet>

    /** A [RequiredSelectionSetRegistry] that returns empty list for every request */
    object Empty : RequiredSelectionSetRegistry {
        override fun getRequiredSelectionSetsForField(
            typeName: String,
            fieldName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> = emptyList()

        override fun getFieldResolverRequiredSelectionSets(
            typeName: String,
            fieldName: String,
        ): List<RequiredSelectionSet> = emptyList()

        override fun getFieldCheckerRequiredSelectionSets(
            typeName: String,
            fieldName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> = emptyList()

        override fun getRequiredSelectionSetsForType(
            typeName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> = emptyList()

        override fun getTypeCheckerRequiredSelectionSets(
            typeName: String,
            executeAccessChecksInModstrat: Boolean
        ): List<RequiredSelectionSet> = emptyList()
    }
}
