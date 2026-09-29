package model.registry

import model.Arguments
import model.InclusionCondition
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.instantiateVariables
import model.materializeSelectionForestOf
import model.objectKey
import model.selectionForestOf
import model.usedVariables
import viaduct.graphql.schema.ViaductSchema

/** Instantiates one object- or Query-rooted fragment for resolution processing. */
internal fun instantiateResolverFragment(
    resolverOccurrenceId: ResolverOccurrenceId,
    constructionSelections: SelectionForest,
    variables: Map<Arguments.Variable, VariableDefinition>,
    fieldPathInclusionConditions: Map<Arguments.Variable, List<InclusionCondition>>,
): ResolverFragment {
    val instantiatedSelections = constructionSelections.instantiateVariables(resolverOccurrenceId)
    val usedVariables = instantiatedSelections.usedVariables()
    val variableDefinitions =
        variables.mapNotNull { (variable, definition) ->
            VariableInstanceDefinition.of(
                variable = variable.instantiate(resolverOccurrenceId),
                definition = definition,
            ).takeIf { it.variable in usedVariables }
        }
    val pathVariableDefinitions =
        fieldPathInclusionConditions.map { (variable, conditions) ->
            val definition = variables.getValue(variable) as VariableDefinition.FromField
            InstantiatedFieldPathDefinition.of(
                variable = variable.instantiate(resolverOccurrenceId),
                providerFragment = definition.providerFragment,
                path =
                    definition.path.mapIndexed { index, key ->
                        InstantiatedFieldPathElement.of(
                            key =
                                ObjectEngineResult.Key.of(
                                    field = key.field,
                                    arguments =
                                        key.arguments.instantiateVariables(
                                            key.field,
                                            resolverOccurrenceId,
                                        ),
                                ),
                            inclusionCondition =
                                conditions[index].mapVariables { template ->
                                    template.instantiate(resolverOccurrenceId)
                                },
                        )
                    },
            )
        }
    return ResolverFragment(
        resolverOccurrenceId = resolverOccurrenceId,
        constructionSelections = instantiatedSelections,
        variableDefinitions = variableDefinitions,
        pathVariableDefinitions = pathVariableDefinitions,
    )
}

internal fun Map<Arguments.Variable, VariableDefinition>.fieldPathInclusionConditions(
    providerFragment: ProviderFragment,
    materializeSelections: MaterializeSelectionForest,
): Map<Arguments.Variable, List<InclusionCondition>> =
    mapNotNull { (variable, definition) ->
        (definition as? VariableDefinition.FromField)
            ?.takeIf { it.providerFragment == providerFragment }
            ?.let { variable to it.inclusionConditions(materializeSelections) }
    }.toMap()

internal fun MaterializeSelectionForest.requireNoVariablesBeneathParent(inputOwner: String) {
    forEach { selection ->
        if (selection.inclusionCondition === InclusionCondition.Never) return@forEach
        require(
            selection.possibleTypes.all { possibleType ->
                val objectKey = selection.key.objectKey(possibleType)
                objectKey !is ObjectEngineResult.ParentKey ||
                    (objectKey.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)
                        ?.possibleObjectTypes
                        .orEmpty()
                        .all { parentType ->
                            selection.subselections.usedVariablesApplicableTo(parentType).isEmpty()
                        }
            },
        ) {
            "$inputOwner must not use variables beneath @parent field " +
                "${selection.key.field.containingDef.name}/${selection.key.field.name}"
        }
        selection.subselections.requireNoVariablesBeneathParent(inputOwner)
    }
}

private fun MaterializeSelectionForest.usedVariablesApplicableTo(type: ViaductSchema.Object): Set<Arguments.Variable> {
    val variables = linkedSetOf<Arguments.Variable>()
    forEach { selection ->
        if (selection.inclusionCondition === InclusionCondition.Never) return@forEach
        if (type !in selection.possibleTypes) return@forEach
        val objectKey = selection.key.objectKey(type)
        variables += objectKey.arguments.usedVariables()
        variables += selection.inclusionCondition.usedVariables()
        val outputType = objectKey.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
        outputType?.possibleObjectTypes?.forEach { possibleOutputType ->
            variables += selection.subselections.usedVariablesApplicableTo(possibleOutputType)
        }
    }
    return variables
}

private fun SelectionForest.instantiateVariables(resolverOccurrenceId: ResolverOccurrenceId): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.instantiateVariables(
                                selection.key.field,
                                resolverOccurrenceId,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                inclusionCondition =
                    selection.inclusionCondition.mapVariables { variable ->
                        variable.instantiate(resolverOccurrenceId)
                    },
                subselections =
                    selection.subselections.instantiateVariables(
                        resolverOccurrenceId,
                    ),
            ),
        )
    }

internal fun MaterializeSelectionForest.instantiateVariables(resolverOccurrenceId: ResolverOccurrenceId): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.instantiateVariables(
                                selection.key.field,
                                resolverOccurrenceId,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                inclusionCondition =
                    selection.inclusionCondition.mapVariables { variable ->
                        variable.instantiate(resolverOccurrenceId)
                    },
                fieldDirectives = selection.fieldDirectives,
                subselections =
                    selection.subselections.instantiateVariables(
                        resolverOccurrenceId,
                    ),
            ),
        )
    }
