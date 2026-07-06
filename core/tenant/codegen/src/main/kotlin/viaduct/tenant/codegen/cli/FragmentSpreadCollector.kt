package viaduct.tenant.codegen.cli

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import viaduct.engine.api.parse.DocumentParser

/** Shared BFS resolving named-fragment spreads, used by RSS assembly and @GraphQLOperation validation. */
internal object FragmentSpreadCollector {
    /**
     * Collects fragment-spread names transitively reachable from [roots] that are in [knownFragments]
     * but not in [alreadyDefined] (locally-defined names are skipped, so they shadow external ones).
     */
    fun collectReachableExternalFragments(
        roots: List<SelectionSet>,
        knownFragments: Map<String, String>,
        alreadyDefined: Set<String>,
    ): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()
        val queue = ArrayDeque<SelectionSet>()
        roots.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.selections.forEach { selection ->
                when (selection) {
                    is FragmentSpread -> {
                        val name = selection.name
                        if (visited.add(name) && name !in alreadyDefined && name in knownFragments) {
                            result.add(name)
                            // Parse the fragment body to find its own spreads transitively.
                            DocumentParser.parse(knownFragments.getValue(name))
                                .getDefinitionsOfType(FragmentDefinition::class.java)
                                .mapNotNull { it.selectionSet }
                                .forEach { queue.add(it) }
                        }
                    }

                    is Field -> selection.selectionSet?.let { queue.add(it) }
                    is InlineFragment -> queue.add(selection.selectionSet)
                }
            }
        }
        return result
    }
}
