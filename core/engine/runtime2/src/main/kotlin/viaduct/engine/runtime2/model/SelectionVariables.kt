package viaduct.engine.runtime2.model

import java.util.IdentityHashMap

/**
 * Binds or qualifies variables without copying the descendant graph. Each source forest gets one
 * view in this binding environment; aliases, directives and shared fragment children survive.
 * Transformations must be pure. No bound view is cached on the reusable source graph.
 */
internal fun SelectionForest.mapSelectionVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
    mapArguments: (ObjectEngineResult.Key) -> Arguments = { it.arguments.mapVariableTemplates(it.field, transform) },
): SelectionForest {
    if (!containsVariables()) return this
    val views = IdentityHashMap<SelectionForest, SelectionForest>()

    fun view(source: SelectionForest): SelectionForest =
        if (!source.containsVariables()) {
            source
        } else {
            synchronized(views) {
                views.getOrPut(source) {
                    deferredSelectionForest {
                        source.mapSelections { selection ->
                            Selection.of(
                                key = ObjectEngineResult.Key.of(selection.key.field, mapArguments(selection.key)),
                                possibleTypes = selection.possibleTypes,
                                subselections = view(selection.subselections),
                                inclusionCondition = selection.inclusionCondition.mapVariables(transform),
                                responseKey = selection.responseKey,
                                fieldDirectives = selection.fieldDirectives,
                            )
                        }
                    }
                }
            }
        }
    return view(this)
}
