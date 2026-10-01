package model.parsing

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import model.EngineInputData
import model.Fragment
import model.lowering.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.SelectionsParserUtils

/** Decodes a fragment document with source response keys and explicit ownership for unbound variables. */
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
    val (nominalType, selections) = materializeSelectionsFrom(
        source = AstPrinter.printAst(inlinedEntry),
        bindings = bindings,
        variableField = variableField,
        preserveSourceResponseKeys = true,
    )
    return Fragment.of(nominalType, selections)
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
