package viaduct.engine.runtime2.model

/** Maps variables while retaining response keys, field directives and source occurrence order. */
internal fun SelectionForest.mapSelectionVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
    mapArguments: (ObjectEngineResult.Key) -> Arguments = { it.arguments.mapVariableTemplates(it.field, transform) },
): SelectionForest {
    if (!containsVariables()) return this
    return mapSelections { selection ->
        Selection.of(
            key =
                if (selection.key.arguments is Arguments.Ground) {
                    selection.key
                } else {
                    ObjectEngineResult.Key.of(selection.key.field, mapArguments(selection.key))
                },
            possibleTypes = selection.possibleTypes,
            subselections = selection.subselections.mapSelectionVariables(transform, mapArguments),
            inclusionCondition = selection.inclusionCondition.mapVariables(transform),
            responseKey = selection.responseKey,
            fieldDirectives = selection.fieldDirectives,
        )
    }
}
