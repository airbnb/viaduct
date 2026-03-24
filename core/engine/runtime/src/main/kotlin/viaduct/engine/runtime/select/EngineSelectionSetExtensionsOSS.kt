package viaduct.engine.runtime.select

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.TypeName
import viaduct.engine.api.EngineSelectionSet
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName
import viaduct.utils.string.sha256Hash

/** Generate a hash string based on the selections in this EngineSelectionSet */
fun EngineSelectionSet.hash(): String = this.printAsFieldSet().sha256Hash()

/**
 * Render this EngineSelectionSet into a graphql-java [graphql.language.Document].
 * Any fragment spreads that are used by this EngineSelectionSet will be converted into inline fragments
 */
fun EngineSelectionSet.toDocument(fragmentName: String = EntryPointFragmentName): Document =
    if (isEmpty()) {
        Document(emptyList())
    } else {
        Document(listOf(toFragmentDefinition(fragmentName)))
    }

/**
 * Render this EngineSelectionSet into a graphql-java [graphql.language.FragmentDefinition].
 * Any fragment spreads that are used by this EngineSelectionSet will be converted into inline fragments.
 */
fun EngineSelectionSet.toFragmentDefinition(fragmentName: String = EntryPointFragmentName): FragmentDefinition =
    FragmentDefinition.newFragmentDefinition()
        .name(fragmentName)
        .typeCondition(TypeName(type))
        .selectionSet(toSelectionSet())
        .build()
