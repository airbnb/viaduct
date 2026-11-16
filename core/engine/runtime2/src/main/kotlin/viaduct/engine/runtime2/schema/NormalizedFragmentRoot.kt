package viaduct.engine.runtime2.schema

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.normalized.NormalizedDocument
import graphql.normalized.NormalizedSelectionScope
import graphql.normalized.NormalizedSelectionSet

/**
 * ND exposes operation roots, while resolver templates are standalone fragments. Validation has
 * already normalized every field and its children. Add only missing fragment/inline scope roots,
 * reusing the validated fields and existing child IDs. This does not expand fragment occurrences.
 */
internal fun NormalizedDocument.withFragmentRoot(fragment: FragmentDefinition): Pair<NormalizedDocument, Int> {
    val ids = mutableMapOf<SelectionSet, Int>()
    val sets = selectionSets.toMutableList()
    fields.values.forEach { field -> field.source.selectionSet?.let { ids[it] = field.selectionSetId } }
    selectionSets.forEach { set ->
        set.selections.filterIsInstance<NormalizedSelectionScope>().forEach { scope ->
            val source = scope.source
            val ast = if (source is InlineFragment) source.selectionSet else requireNotNull(scope.fragmentDefinition).selectionSet
            ids[ast] = scope.selectionSetId
        }
    }
    val pending = ArrayDeque<SelectionSet>()

    fun id(ast: SelectionSet): Int =
        ids.getOrPut(ast) {
            sets.size.also {
                sets.add(NormalizedSelectionSet.newNormalizedSelectionSet().selections(emptyList()).build())
                pending.addLast(ast)
            }
        }
    val root = id(fragment.selectionSet)
    val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
    while (pending.isNotEmpty()) {
        val ast = pending.removeFirst()
        sets[ids.getValue(ast)] = NormalizedSelectionSet.newNormalizedSelectionSet().selections(
            ast.selections.map { selection ->
                when (selection) {
                    is Field -> fields.getValue(selection)
                    is InlineFragment -> NormalizedSelectionScope.newNormalizedSelectionScope().source(selection)
                        .typeCondition(selection.typeCondition?.name).selectionSetId(id(selection.selectionSet)).build()
                    is FragmentSpread -> {
                        val target = fragments.getValue(selection.name)
                        NormalizedSelectionScope.newNormalizedSelectionScope().source(selection).typeCondition(target.typeCondition.name)
                            .fragmentDefinition(target).selectionSetId(id(target.selectionSet)).build()
                    }
                    else -> error("Unexpected validated selection")
                }
            }
        ).build()
    }
    return transform { it.selectionSets(sets) } to root
}
