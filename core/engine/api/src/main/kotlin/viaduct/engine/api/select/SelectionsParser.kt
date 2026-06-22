package viaduct.engine.api.select

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils

/**
 * Parses GraphQL selection-set representations into [viaduct.graphql.utils.ParsedSelections].
 *
 * Provides three entry points:
 * - [parse] from a [Fragment] — extracts the fragment definition's selection set directly.
 * - [parse] from a type name and a `@[Selections]` string — handles both shorthand and full
 *   fragment forms, caching the underlying parse via [CachedDocumentParser].
 * - [fromDataFetchingEnvironment] — reconstructs the active selection set for a field from the
 *   GraphQL-Java [graphql.schema.DataFetchingEnvironment] during execution.
 */
object SelectionsParser {
    /** Return a [ParsedSelections] from the provided [Fragment] */
    fun parse(fragment: Fragment): ParsedSelections =
        ParsedSelections(
            fragment.definition.typeCondition.name,
            fragment.definition.selectionSet,
            fragment.parsedDocument.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        )

    /**
     * Return a [ParsedSelections] from the provided type and [Selections] string.
     *
     * [knownFragments] is populated only by the classic (reflection-based) bootstrapper
     * ([viaduct.tenant.runtime.bootstrap.ViaductTenantModuleBootstrapper]), which scans the
     * classpath at startup to resolve [@GraphQLFragment][viaduct.api.documents.GraphQLFragment]
     * spreads at runtime. The KSP/codegen path inlines named fragments into the selections string
     * at assembly time, so [knownFragments] is empty there.
     */
    fun parse(
        typeName: String,
        @Selections selections: String,
        knownFragments: Map<String, FragmentDefinition> = emptyMap(),
    ): ParsedSelections {
        val document =
            try {
                if (SelectionsParserUtils.isShorthandForm(selections)) {
                    CachedDocumentParser.parseDocument(SelectionsParserUtils.wrapShorthandAsFragment(selections, typeName))
                } else {
                    CachedDocumentParser.parseDocument(selections)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not parse selections $selections: ${e.message}")
            }

        val parsed = ParsedSelections.fromDocument(typeName, document)
        return if (knownFragments.isEmpty()) {
            parsed
        } else {
            val reachable = collectReachableFragments(parsed.selections, parsed.fragmentMap + knownFragments)
            // Merge: preserve all original fragments (not just reachable ones) and add newly
            // discovered named fragments so that resolvers with no named spread don't lose their
            // existing fragmentMap entries (which would silently skip schema validation).
            ParsedSelections(parsed.typeName, parsed.selections, parsed.fragmentMap + reachable)
        }
    }

    private fun collectReachableFragments(
        selectionSet: SelectionSet,
        allFragments: Map<String, FragmentDefinition>,
    ): Map<String, FragmentDefinition> {
        val visited = mutableMapOf<String, FragmentDefinition>()
        val queue = ArrayDeque<SelectionSet>()
        queue.add(selectionSet)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.selections.forEach { selection ->
                when (selection) {
                    is FragmentSpread -> {
                        val name = selection.name
                        if (name !in visited) {
                            val def = allFragments[name]
                            if (def != null) {
                                visited[name] = def
                                queue.add(def.selectionSet)
                            }
                        }
                    }
                    is Field -> selection.selectionSet?.let { queue.add(it) }
                    is InlineFragment -> queue.add(selection.selectionSet)
                }
            }
        }
        return visited
    }

    /**
     * Return a [ParsedSelections] from the provided type and [DataFetchingEnvironment].
     */
    fun fromDataFetchingEnvironment(
        typeName: String,
        env: DataFetchingEnvironment
    ): ParsedSelections {
        val selections = env.mergedField.fields.mapNotNull { it.selectionSet }
            .flatMap { it.selections }
            .let(::SelectionSet)
        return ParsedSelections(
            typeName,
            selections,
            env.engineExecutionContext.fieldScope.fragments
        )
    }
}
