package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.instantiateVariables
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.mapSelectionVariables
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.toSelectionForest
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.model.withoutProjectionMetadata
import viaduct.graphql.schema.ViaductSchema

// Canonical registries require concrete field keys even when source selections use interfaces.
internal fun Fragment.normalizeRoot(
    expectedType: ViaductSchema.Object,
    role: String,
): SelectionForest {
    require(nominalType == expectedType) {
        "$role type ${nominalType.name} does not match ${expectedType.name}"
    }
    return subselections.flatMap { selection ->
        if (expectedType !in selection.possibleTypes) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    responseKey = selection.responseKey,
                    key = selection.key.objectKey(expectedType),
                    possibleTypes = setOf(expectedType),
                    inclusionCondition = selection.inclusionCondition,
                    fieldDirectives = selection.fieldDirectives,
                    subselections = selection.subselections,
                ),
            )
        }
    }
}

internal fun ObjectSelectionForest.partitionVariableDemand(): Pair<ObjectSelectionForest, ObjectSelectionForest> {
    val (static, dynamic) = partitionVariableSelections()
    // Partitioning preserves concrete keys and root uniqueness in each part.
    return ObjectSelectionForest.of(type, static.map { it as ObjectSelection }) to
        ObjectSelectionForest.of(type, dynamic.map { it as ObjectSelection })
}

internal fun SelectionForest.partitionVariableDemand(): Pair<SelectionForest, SelectionForest> {
    val (static, dynamic) = partitionVariableSelections()
    return static.toSelectionForest() to dynamic.toSelectionForest()
}

private fun SelectionForest.partitionVariableSelections(): Pair<List<Selection>, List<Selection>> {
    val static = mutableListOf<Selection>()
    val dynamic = mutableListOf<Selection>()
    forEach { selection ->
        if (selection.key.arguments.usedVariables().isNotEmpty() || selection.inclusionCondition !== InclusionCondition.Always) {
            dynamic += selection.withoutProjectionMetadata()
        } else {
            val (staticChildren, dynamicChildren) = selection.subselections.partitionVariableDemand()
            if (dynamicChildren.isEmpty()) {
                static += selection.withoutProjectionMetadata()
            } else {
                static += Selection.of(selection.key, selection.possibleTypes, staticChildren)
                dynamic += Selection.of(selection.key, selection.possibleTypes, dynamicChildren)
            }
        }
    }
    return static to dynamic
}

internal fun SelectionForest.requireNoErroneousArguments() {
    forEach { selection ->
        require(selection.key.arguments != Arguments.Error) {
            "Registry fragment contains erroneous arguments for " +
                "${selection.key.field.containingDef.name}/${selection.key.field.name}"
        }
        selection.subselections.requireNoErroneousArguments()
    }
}

/** Instantiates one object- or Query-rooted fragment for resolution processing. */
internal fun instantiateResolverFragment(
    resolverOccurrenceId: ResolverOccurrenceId,
    constructionSelections: SelectionForest,
    usedVariableTemplates: Set<Arguments.Variable>,
    variables: Map<Arguments.Variable, VariableDefinition>,
    fieldPathInclusionConditions: Map<Arguments.Variable, List<InclusionCondition>>,
): ResolverFragment {
    val instantiatedSelections =
        if (usedVariableTemplates.isEmpty()) constructionSelections else constructionSelections.instantiateVariables(resolverOccurrenceId)
    // Normalization can remove conditions still needed by materialization aliases.
    val variableDefinitions =
        variables.mapNotNull { (variable, definition) ->
            if (variable !in usedVariableTemplates) return@mapNotNull null
            VariableInstanceDefinition.of(
                variable = variable.instantiate(resolverOccurrenceId),
                definition = definition,
            )
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
    materializeSelections: SelectionForest,
): Map<Arguments.Variable, List<InclusionCondition>> =
    mapNotNull { (variable, definition) ->
        (definition as? VariableDefinition.FromField)
            ?.takeIf { it.providerFragment == providerFragment }
            ?.let { variable to it.inclusionConditions(materializeSelections) }
    }.toMap()

internal fun SelectionForest.requireNoVariablesBeneathParent(inputOwner: String) {
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

internal fun SelectionForest.requireNoParentFieldSelections(inputOwner: String) {
    forEach { selection ->
        require(!selection.key.field.isParentField()) {
            "$inputOwner must not select @parent field ${selection.key.field.containingDef.name}/${selection.key.field.name}"
        }
        selection.subselections.requireNoParentFieldSelections(inputOwner)
    }
}

private fun SelectionForest.usedVariablesApplicableTo(type: ViaductSchema.Object): Set<Arguments.Variable> {
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

internal fun SelectionForest.instantiateVariables(resolverOccurrenceId: ResolverOccurrenceId): SelectionForest =
    mapSelectionVariables(
        transform = { it.instantiate(resolverOccurrenceId) },
        mapArguments = { it.arguments.instantiateVariables(it.field, resolverOccurrenceId) },
    )
