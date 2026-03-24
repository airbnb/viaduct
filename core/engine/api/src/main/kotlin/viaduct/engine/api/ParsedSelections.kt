package viaduct.engine.api

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.SelectionSet

/** A parsed representation of a [viaduct.api.select.Selections] String */
interface ParsedSelections {
    /** the type described by [selections] */
    val typeName: String

    /** a SelectionSet that selects over [typename] */
    val selections: SelectionSet

    /** a map of GraphQL fragment definitions that may be used by [selections] */
    val fragmentMap: Map<String, FragmentDefinition>

    /**
     * Convert this ParsedSelections into a [Document]
     * The document will include [FragmentDefinitions] for every fragment in [fragmentMap].
     * The document will not include other kinds of definitions.
     */
    fun toDocument(): Document

    /**
     * Filter this ParsedSelections such that it only includes the selections along the provided path.
     * No filtering will be applied past the bounds of the provided path.
     *
     * The returned ParsedSelections may have a different language structure but will be semantically
     * equivalent to a filtered view of the original selections. Two selection sets are considered
     * semantically equivalent if they would produce the same result when executed.
     * For example, the selection sets `{... {a1}}` and `{a1}` are semantically
     * equivalent.
     *
     * **Example 1: Basic filtering**
     *
     * Given selections `{a1, a2 {b1, b2}}`,
     * then filtering by path `[a2, b1]` will return selections semantically equivalent to
     * `{a2 {b1}}`
     *
     * **Example 2: Path is shorter than selections**
     *
     * The provided [path] may be shorter than the depth of selections, in which case selections past
     * the end of the path will be unfiltered.
     *
     * Given selections `{a1, a2 {b1, b2}}`,
     * then filtering by path `[a2]` will return selections semantically equivalent to
     * `{a2 {b1, b2}}`
     */
    fun filterToPath(path: List<String>): ParsedSelections?

    companion object {
        private class Empty(override val typeName: String) : ParsedSelections {
            override val selections: SelectionSet = SelectionSet(emptyList())
            override val fragmentMap: Map<String, FragmentDefinition> = emptyMap()

            override fun toDocument(): Document = Document(emptyList())

            override fun filterToPath(path: List<String>): ParsedSelections? = null
        }

        fun empty(typeName: String): ParsedSelections = Empty(typeName)
    }
}
