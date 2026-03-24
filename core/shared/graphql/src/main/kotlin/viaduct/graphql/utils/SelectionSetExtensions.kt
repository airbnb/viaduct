package viaduct.graphql.utils

import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.SelectionSet

fun SelectionSet.addTypeName(): SelectionSet {
    var fields =
        this.selections.map { sel ->
            when (sel) {
                is InlineFragment -> {
                    val subselections = sel.selectionSet
                    sel.transform {
                        val selSet = subselections.addTypeName()
                        it.selectionSet(selSet)
                    }
                }

                is Field -> {
                    if (sel.selectionSet?.selections?.isNotEmpty() == true) {
                        val subselections = sel.selectionSet
                        sel.transform {
                            val selSet = subselections.addTypeName()
                            it.selectionSet(selSet)
                        }
                    } else {
                        sel
                    }
                }

                else -> sel
            }
        }

    if (fields.none { (it as? Field)?.name == "__typename" }) {
        fields = fields + Field("__typename")
    }

    return this.transform { it.selections(fields) }
}
