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
    ) : FieldEntry(coord)

    /**
     * A RequiredSelectionSet entry for a specific coordinate's checker.
     */
    class FieldCheckerEntry(
        coord: Coordinate,
        override val selectionsType: String,
        override val selectionsString: String,
        override val variablesResolvers: List<VariablesResolver>
    ) : FieldEntry(coord)

    /**
     * A RequiredSelectionSet entry for a specific type checker.
     */
    data class TypeCheckerEntry(
        val typeName: String,
        override val selectionsType: String,
        override val selectionsString: String,
        override val variablesResolvers: List<VariablesResolver>
    ) : RequiredSelectionSetEntry()

    /** merge this registry with the provided registry */
    operator fun plus(other: MockRequiredSelectionSetRegistry): MockRequiredSelectionSetRegistry = MockRequiredSelectionSetRegistry(other.entries + entries)

    override fun getFieldResolverRequiredSelectionSets(
        typeName: String,
        fieldName: String,
    ): List<RequiredSelectionSet> =
        entries
            .filterIsInstance<FieldResolverEntry>()
            .filter { it.coord == (typeName to fieldName) }
            .map { mkRSS(it.selectionsType, it.selectionsString, it.variablesResolvers) }

    override fun getFieldCheckerRequiredSelectionSets(
        typeName: String,
        fieldName: String,
        executeAccessChecksInModstrat: Boolean
    ): List<RequiredSelectionSet> =
        entries
            .filterIsInstance<FieldCheckerEntry>()
            .filter { it.coord == (typeName to fieldName) }
            .map { mkRSS(it.selectionsType, it.selectionsString, it.variablesResolvers) }

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
            .map { mkRSS(it.selectionsType, it.selectionsString, it.variablesResolvers) }

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
                variablesResolvers: List<VariablesResolver> = emptyList()
            ): Builder = add(FieldCheckerEntry(coord, coord.first, selectionsString, variablesResolvers))

            fun typeCheckerEntry(
                typeName: String,
                selectionsString: String,
                variablesResolvers: List<VariablesResolver> = emptyList()
            ): Builder = add(TypeCheckerEntry(typeName, typeName, selectionsString, variablesResolvers))

            fun build(): MockRequiredSelectionSetRegistry = MockRequiredSelectionSetRegistry(entries)
        }

        fun builder(): Builder = Builder()
    }
}
