package viaduct.graphql.utils

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.Node
import graphql.language.Selection as GJSelection
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

/** A parsed representation of a GraphQL selections string */
class ParsedSelections(
    /** the type described by [selections] */
    val typeName: String,
    /** a SelectionSet that selects over [typeName] */
    val selections: GJSelectionSet,
    /** a map of GraphQL fragment definitions that may be used by [selections] */
    val fragmentMap: Map<String, FragmentDefinition>
) {
    /**
     * Convert this ParsedSelections into a [Document]
     * The document will include [FragmentDefinition]s for every fragment in [fragmentMap].
     * The document will not include other kinds of definitions.
     */
    fun toDocument(): Document =
        Document.newDocument()
            .definitions(fragmentMap.values.toList())
            .build()

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
    fun filterToPath(path: List<String>): ParsedSelections? = PathFilter(this).filter(path)

    override fun toString(): String = AstPrinter.printAst(toDocument())

    companion object {
        fun equals(
            parsedSelections: ParsedSelections?,
            other: Any?
        ): Boolean {
            if (parsedSelections === other) return true
            if (parsedSelections == null || other !is ParsedSelections) return false

            // do cheap checks first
            if (parsedSelections.typeName != other.typeName) return false
            if (parsedSelections.fragmentMap.size != other.fragmentMap.size) return false
            if (parsedSelections.fragmentMap.keys != other.fragmentMap.keys) return false

            // do more expensive checks
            if (!nodesEqual(parsedSelections.selections, other.selections)) return false
            return parsedSelections.fragmentMap.all { (name, def) ->
                nodesEqual(def, other.fragmentMap[name]!!)
            }
        }

        fun empty(typeName: String): ParsedSelections =
            ParsedSelections(
                typeName,
                GJSelectionSet(emptyList()),
                emptyMap()
            )

        fun fromDocument(
            typeName: String,
            document: Document
        ): ParsedSelections {
            val fragments = document.definitions.map {
                it as? FragmentDefinition
                    ?: throw IllegalArgumentException("selections string may only contain fragment definitions. Found: $it")
            }

            val fragmentMap = fragments.associateBy { it.name }.also {
                if (fragments.size != it.size) {
                    val dupes = fragments.groupBy { it.name }.filter { it.value.size > 1 }.keys
                    throw IllegalArgumentException("Document contains repeated definitions for fragments with names: $dupes")
                }
            }

            val entryFragment = SelectionsParserUtils.findEntryPointFragment(fragments)
            require(entryFragment.typeCondition.name == typeName) {
                "Fragment ${entryFragment.name} must be on type $typeName"
            }

            return ParsedSelections(typeName, entryFragment.selectionSet, fragmentMap)
        }

        private fun nodesEqual(
            a: Node<*>,
            b: Node<*>
        ): Boolean = AstPrinter.printAstCompact(a) == AstPrinter.printAstCompact(b)
    }
}

private class PathFilter(val parsedSelections: ParsedSelections) {
    fun filter(path: List<String>): ParsedSelections? =
        filterToPath(parsedSelections.selections, path)
            ?.let { filtered ->
                val entrypointFragment = FragmentDefinition.newFragmentDefinition()
                    .name(EntryPointFragmentName)
                    .typeCondition(TypeName(parsedSelections.typeName))
                    .selectionSet(filtered)
                    .build()
                ParsedSelections(
                    parsedSelections.typeName,
                    filtered,
                    mapOf(entrypointFragment.name to entrypointFragment)
                )
            }

    private fun filterToPath(
        selectionSet: GJSelectionSet,
        path: List<String>
    ): GJSelectionSet? {
        return selectionSet.selections
            .mapNotNull { filterToPath(it, path) }
            .takeIf { it.isNotEmpty() }
            ?.let(::GJSelectionSet)
    }

    private fun filterToPath(
        selection: GJSelection<*>,
        path: List<String>
    ): GJSelection<*>? {
        val segment = path.firstOrNull()
        return when (selection) {
            is Field -> {
                selection.takeIf { it.resultKey == segment || segment == null }
                    ?.let { field ->
                        // traverse into subselections
                        if (field.selectionSet != null) {
                            filterToPath(field.selectionSet, path.drop(1))
                                ?.let { filteredSelectionSet ->
                                    field.transform {
                                        it.selectionSet(filteredSelectionSet)
                                    }
                                }
                        } else if (path.size > 1) {
                            // if there are additional path segments and the field does not have subselections, then
                            // drop this field since it has no subtree that can satisfy the filter
                            null
                        } else {
                            field
                        }
                    }
            }

            is InlineFragment ->
                filterToPath(selection.selectionSet, path)
                    ?.let { filteredSelectionSet ->
                        selection.transform {
                            it.selectionSet(filteredSelectionSet)
                        }
                    }

            is FragmentSpread -> {
                val fragment = checkNotNull(parsedSelections.fragmentMap[selection.name]) {
                    "Missing fragment: ${selection.name}"
                }
                // inline any fragment spreads that contained selections after filtering
                filterToPath(fragment.selectionSet, path)
                    ?.let { filteredSelectionSet ->
                        InlineFragment.newInlineFragment()
                            .directives(selection.directives)
                            .typeCondition(fragment.typeCondition)
                            .selectionSet(filteredSelectionSet)
                            .build()
                    }
            }

            else -> throw IllegalArgumentException("Unsupported selection type: $selection")
        }
    }
}
