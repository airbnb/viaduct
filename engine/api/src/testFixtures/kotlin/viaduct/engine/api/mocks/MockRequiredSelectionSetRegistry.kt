package viaduct.engine.api.mocks

import viaduct.engine.api.Coordinate
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.VariablesResolver

class MockRequiredSelectionSetRegistry(
    val entries: List<RequiredSelectionSetEntry> = emptyList()
) : RequiredSelectionSetRegistry {
    sealed class RequiredSelectionSetEntry {
        abstract val selectionsType: String
        abstract val selectionsString: String
        abstract val variablesResolvers: List<VariablesResolver>

        /**
         * Ensures each RSS is only constructed once like the actual registry
         */
        abstract val rss: RequiredSelectionSet
    }

    abstract class FieldEntry(
        val coord: Coordinate,
    ) : RequiredSelectionSetEntry()

    /**
     * A RequiredSelectionSet entry for a specific coordinate's resolver.
     */
    class FieldResolverEntry(
        coord: Coordinate,
        override val selectionsType: String,
        override val selectionsString: String,
        override val variablesResolvers: List<VariablesResolver>
    ) : FieldEntry(coord) {
        override val rss: RequiredSelectionSet by lazy {
            mkRSS(selectionsType, selectionsString, variablesResolvers, forChecker = false)
        }
    }

    /**
     * A RequiredSelectionSet entry for a specific coordinate's checker.
     */
    class FieldCheckerEntry(
        coord: Coordinate,
        override val selectionsType: String,
        override val selectionsString: String,
        override val variablesResolvers: List<VariablesResolver>
    ) : FieldEntry(coord) {
        override val rss: RequiredSelectionSet by lazy {
            mkRSS(selectionsType, selectionsString, variablesResolvers, forChecker = true)
        }
    }

    /**
     * A RequiredSelectionSet entry for a specific type checker.
     */
    data class TypeCheckerEntry(
        val typeName: String,
        override val selectionsType: String,
        override val selectionsString: String,
        override val variablesResolvers: List<VariablesResolver>
    ) : RequiredSelectionSetEntry() {
        override val rss: RequiredSelectionSet by lazy {
            mkRSS(selectionsType, selectionsString, variablesResolvers, forChecker = true)
        }
    }

    /** merge this registry with the provided registry */
    operator fun plus(other: MockRequiredSelectionSetRegistry): MockRequiredSelectionSetRegistry = MockRequiredSelectionSetRegistry(other.entries + entries)

    override fun getFieldResolverRequiredSelectionSets(
        typeName: String,
        fieldName: String,
    ): List<RequiredSelectionSet> =
        entries
            .filterIsInstance<FieldResolverEntry>()
            .filter { it.coord == (typeName to fieldName) }
            .map { it.rss }

    override fun getFieldCheckerRequiredSelectionSets(
        typeName: String,
        fieldName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> =
        entries
            .filterIsInstance<FieldCheckerEntry>()
            .filter { it.coord == (typeName to fieldName) }
            .map { it.rss }

    fun getRequiredSelectionSetsForField(
        typeName: String,
        fieldName: String
    ): List<RequiredSelectionSet> = getRequiredSelectionSetsForField(typeName, fieldName, true)

    override fun getTypeCheckerRequiredSelectionSets(
        typeName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> = getRequiredSelectionSetsForType(typeName)

    fun getRequiredSelectionSetsForType(typeName: String): List<RequiredSelectionSet> =
        entries
            .filterIsInstance<TypeCheckerEntry>()
            .filter { it.typeName == typeName }
            .map { it.rss }

    companion object {
        val empty: MockRequiredSelectionSetRegistry = MockRequiredSelectionSetRegistry()

        class Builder {
            private val entries = mutableListOf<RequiredSelectionSetEntry>()

            fun add(entry: RequiredSelectionSetEntry): Builder =
                this.also {
                    entries += entry
                }

            fun fieldResolverEntry(
                coord: Coordinate,
                selectionsString: String,
                variablesResolvers: List<VariablesResolver> = emptyList()
            ): Builder = fieldResolverEntryForType(coord.first, coord, selectionsString, variablesResolvers)

            fun fieldResolverEntryForType(
                selectionsType: String,
                coord: Coordinate,
                selectionsString: String,
                variablesResolvers: List<VariablesResolver> = emptyList()
            ): Builder = add(FieldResolverEntry(coord, selectionsType, selectionsString, variablesResolvers.toList()))

            fun fieldCheckerEntry(
                coord: Coordinate,
                selectionsString: String,
                variablesResolvers: List<VariablesResolver> = emptyList(),
                selectionsType: String = coord.first
            ): Builder = add(FieldCheckerEntry(coord, selectionsType, selectionsString, variablesResolvers))

            fun typeCheckerEntry(
                typeName: String,
                selectionsString: String,
                variablesResolvers: List<VariablesResolver> = emptyList(),
                selectionsType: String = typeName
            ): Builder = add(TypeCheckerEntry(typeName, selectionsType, selectionsString, variablesResolvers))

            fun build(): MockRequiredSelectionSetRegistry = MockRequiredSelectionSetRegistry(entries)
        }

        fun builder(): Builder = Builder()
    }
}
