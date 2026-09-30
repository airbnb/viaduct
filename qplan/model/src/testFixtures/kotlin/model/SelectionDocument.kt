package model

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import model.testing.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.SelectionsParserUtils

/**
 * Converts one post-validation fragment document into the normalized model fragment.
 *
 * Named fragment definitions remain visible at this boundary. The current selection carrier has no
 * named-spread representation, so this conversion lowers each spread to an inline fragment. Keeping
 * that policy in model fixture preparation allows a future carrier to preserve and share named
 * fragments without changing execution adapters.
 */
fun ViaductAndGJSchema.fragmentFromDocument(
    document: Document,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
): Fragment {
    val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java)
    require(fragments.size == document.definitions.size) {
        "A resolver selection document may contain only fragment definitions"
    }
    val fragmentsByName = fragments.associateBy(FragmentDefinition::getName)
    require(fragmentsByName.size == fragments.size) {
        "A resolver selection document may not contain duplicate fragment definitions"
    }
    val entry = SelectionsParserUtils.findEntryPointFragment(fragments)
    val inlinedEntry =
        entry.transform { builder ->
            builder.selectionSet(
                entry.selectionSet.inlineNamedFragments(
                    fragmentsByName = fragmentsByName,
                    activeFragments = listOf(entry.name),
                ),
            )
        }
    return fragmentFrom(
        source = AstPrinter.printAst(inlinedEntry),
        bindings = bindings,
        variableField = variableField,
        preserveSourceResponseKeys = true,
    )
}

private fun SelectionSet.inlineNamedFragments(
    fragmentsByName: Map<String, FragmentDefinition>,
    activeFragments: List<String>,
): SelectionSet =
    SelectionSet(
        selections.map { selection ->
            when (selection) {
                is Field ->
                    selection.selectionSet?.let { children ->
                        selection.transform { builder ->
                            builder.selectionSet(
                                children.inlineNamedFragments(
                                    fragmentsByName,
                                    activeFragments,
                                ),
                            )
                        }
                    } ?: selection

                is InlineFragment ->
                    selection.transform { builder ->
                        builder.selectionSet(
                            selection.selectionSet.inlineNamedFragments(
                                fragmentsByName,
                                activeFragments,
                            ),
                        )
                    }

                is FragmentSpread -> {
                    val definition =
                        requireNotNull(fragmentsByName[selection.name]) {
                            "Missing named fragment definition: ${selection.name}"
                        }
                    require(selection.name !in activeFragments) {
                        "Named fragment cycle: " +
                            (activeFragments + selection.name).joinToString(" -> ")
                    }
                    InlineFragment.newInlineFragment()
                        .typeCondition(definition.typeCondition)
                        .directives(definition.directives + selection.directives)
                        .selectionSet(
                            definition.selectionSet.inlineNamedFragments(
                                fragmentsByName,
                                activeFragments + selection.name,
                            ),
                        )
                        .build()
                }

                else -> throw IllegalArgumentException("Unexpected GraphQL selection: $selection")
            }
        },
    )
