package viaduct.tenant.codegen.cli

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import viaduct.engine.api.parse.DocumentParser

/** Shared BFS resolving named-fragment spreads, used by RSS assembly and @GraphQLOperation validation. */
internal object FragmentSpreadCollector {
    /**
     * Returns [document] with the external [knownFragments] transitively reachable from its spreads
     * appended, so it is self-contained for schema validation. Fragments already defined in
     * [document] are left untouched (a local definition shadows a same-named external one). If
     * nothing external is reachable, [document] is returned unchanged.
     */
    fun appendReachableExternalFragments(
        document: Document,
        knownFragments: Map<String, String>,
    ): Document {
        if (knownFragments.isEmpty()) return document

        val localNames = document.getDefinitionsOfType(FragmentDefinition::class.java).map { it.name }.toSet()
        val reachable = collectReachableExternalFragments(
            roots = document.definitions.mapNotNull {
                when (it) {
                    is OperationDefinition -> it.selectionSet
                    is FragmentDefinition -> it.selectionSet
                    else -> null
                }
            },
            knownFragments = knownFragments,
            alreadyDefined = localNames,
        )
        if (reachable.isEmpty()) return document

        val builder = Document.newDocument()
        document.definitions.forEach(builder::definition)
        reachable.forEach { name ->
            DocumentParser.parse(knownFragments.getValue(name))
                .getDefinitionsOfType(FragmentDefinition::class.java)
                .forEach(builder::definition)
        }
        return builder.build()
    }

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
