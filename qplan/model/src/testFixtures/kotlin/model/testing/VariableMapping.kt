package model.testing

import model.Arguments

import model.ObjectEngineResult

import model.Fragment
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.Selection
import model.SelectionForest
import model.materializeSelectionForestOf
import model.mapVariableTemplates
import model.selectionForestOf

internal fun Fragment.mapVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        materializeSelections = materializeSelections.mapVariables(transform),
    )

internal fun List<ObjectEngineResult.Key>.mapVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
): List<ObjectEngineResult.Key> =
    map { key ->
        ObjectEngineResult.Key.of(
            field = key.field,
            arguments = key.arguments.mapVariableTemplates(key.field, transform),
        )
    }

private fun SelectionForest.mapVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.mapVariableTemplates(
                                selection.key.field,
                                transform,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                inclusionCondition = selection.inclusionCondition.mapVariables(transform),
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }

private fun MaterializeSelectionForest.mapVariables(
    transform: (Arguments.Variable) -> Arguments.Variable,
): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.mapVariableTemplates(
                                selection.key.field,
                                transform,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                inclusionCondition = selection.inclusionCondition.mapVariables(transform),
                fieldDirectives = selection.fieldDirectives,
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }
