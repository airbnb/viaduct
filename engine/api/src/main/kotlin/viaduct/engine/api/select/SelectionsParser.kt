package viaduct.engine.api.select

import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.api.select.Constants.EntryPointFragmentName

object SelectionsParser {
    private val fragmentRegex = Regex("^\\s*fragment\\s+")

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
                if (isFieldSet(selections)) {
                    val docString =
                        """
                        fragment $EntryPointFragmentName on $typeName {
                            $selections
                        }
                        """.trimIndent()
                    CachedDocumentParser.parseDocument(docString)
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

    private fun entryPointFragment(
        typeName: String,
        fragments: List<FragmentDefinition>
    ): FragmentDefinition {
        val entry =
            if (fragments.size == 1) {
                fragments.first()
            } else {
                fragments.find { it.name == EntryPointFragmentName }
            }
        requireNotNull(entry) {
            "selections must contain only 1 fragment or have 1 fragment definition named Main"
        }
        require(entry.typeCondition.name == typeName) {
            "Fragment ${entry.name} must be on type $typeName"
        }
        return entry
    }

    private fun isFieldSet(s: String): Boolean = !s.contains(fragmentRegex)
}
