package viaduct.engine.api.select

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
import viaduct.engine.api.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

data class ParsedSelectionsImpl(
    override val typeName: String,
    override val selections: GJSelectionSet,
    override val fragmentMap: Map<String, FragmentDefinition>
) : ParsedSelections {
    override fun toDocument(): Document =
        Document.newDocument()
            .definitions(fragmentMap.values.toList())
            .build()

    override fun filterToPath(path: List<String>): ParsedSelectionsImpl? = PathFilter(this).filter(path)

    override fun equals(other: Any?): Boolean {
        if (other !is ParsedSelections) return false

        // do cheap checks first
        if (typeName != other.typeName) return false
        if (fragmentMap.size != other.fragmentMap.size) return false
        if (fragmentMap.keys != other.fragmentMap.keys) return false

        // do more expensive checks
        if (!nodesEqual(selections, other.selections)) return false
        return fragmentMap.all { (name, def) -> nodesEqual(def, other.fragmentMap[name]!!) }
    }

    private fun nodesEqual(
        a: Node<*>,
        b: Node<*>
    ): Boolean = AstPrinter.printAstCompact(a) == AstPrinter.printAstCompact(b)

    override fun toString(): String = AstPrinter.printAst(toDocument())
}

private class PathFilter(val parsedSelections: ParsedSelectionsImpl) {
    fun filter(path: List<String>): ParsedSelectionsImpl? =
        filterToPath(parsedSelections.selections, path)
            ?.let { filtered ->
                val entrypointFragment = FragmentDefinition.newFragmentDefinition()
                    .name(EntryPointFragmentName)
                    .typeCondition(TypeName(parsedSelections.typeName))
                    .selectionSet(filtered)
                    .build()
                parsedSelections.copy(selections = filtered, fragmentMap = mapOf(entrypointFragment.name to entrypointFragment))
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
