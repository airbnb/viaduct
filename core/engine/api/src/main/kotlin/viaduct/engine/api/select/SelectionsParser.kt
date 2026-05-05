package viaduct.engine.api.select

import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.runtime.dfe.engineExecutionContext
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.SelectionsParserUtils

object SelectionsParser {
    /** Return a [ParsedSelections] from the provided [Fragment] */
    fun parse(fragment: Fragment): ParsedSelections =
        ParsedSelections(
            fragment.definition.typeCondition.name!!,
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

        return ParsedSelections.fromDocument(typeName, document)
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
