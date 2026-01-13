package viaduct.engine.api.select

import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.graphql.utils.SelectionsParserUtils

object SelectionsParser {
    /** Return a [ParsedSelections] from the provided [Fragment] */
    fun parse(fragment: Fragment): ParsedSelections =
        ParsedSelectionsImpl(
            fragment.definition.typeCondition.name,
            fragment.definition.selectionSet,
            fragment.parsedDocument.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        )

    /** Return a [ParsedSelections] from the provided type and [Selections] string */
    fun parse(
        typeName: String,
        @Selections selections: String
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

        val fragments =
            document.definitions.mapNotNull {
                it as? FragmentDefinition
                    ?: throw IllegalArgumentException("selections string may only contain fragment definitions. Found: $it")
            }

        val fragmentMap =
            fragments
                .associateBy { it.name }
                .also {
                    if (fragments.size != it.size) {
                        val dupes =
                            fragments.groupBy { it.name }
                                .mapNotNull { (k, v) -> if (v.size > 1) k else null }
                        throw IllegalArgumentException(
                            "Document contains repeated definitions for fragments with names: $dupes"
                        )
                    }
                }

        return ParsedSelectionsImpl(
            typeName,
            entryPointFragment(typeName, fragments).selectionSet,
            fragmentMap
        )
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
        return ParsedSelectionsImpl(
            typeName,
            selections,
            env.engineExecutionContext.fieldScope.fragments
        )
    }

    /**
     * Determines the entry point fragment from a list of fragment definitions and validates its type.
     * - If there's exactly one fragment, it's used as the entry point
     * - If there are multiple fragments, the one named [SelectionsParserUtils.EntryPointFragmentName] is used
     *
     * @param typeName The expected type name for the entry point fragment
     * @param fragments The list of fragment definitions to search
     * @return The entry point fragment definition
     * @throws IllegalArgumentException if no valid entry point fragment is found or type mismatch
     */
    private fun entryPointFragment(
        typeName: String,
        fragments: List<FragmentDefinition>
    ): FragmentDefinition {
        val entry = SelectionsParserUtils.findEntryPointFragment(fragments)
        require(entry.typeCondition.name == typeName) {
            "Fragment ${entry.name} must be on type $typeName"
        }
        return entry
    }
}
