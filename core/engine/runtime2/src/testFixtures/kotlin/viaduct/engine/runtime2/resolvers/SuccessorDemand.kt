package viaduct.engine.runtime2.resolvers

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.flatMapToSelectionForest
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.substituteTemplates
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolution.framework.liftParentSuccessorDemand
import viaduct.graphql.schema.ViaductSchema

/** Extends this demand with every encountered successor resolver's transitive input demand. */
fun SelectionForest.successorDemand(operation: SharedOperationContext<*>): SelectionForest {
    val demand = successorDemandWithoutParentLifting(operation)
    return demand + demand.liftParentSuccessorDemand(operation.world)
}

/**
 * Computes producer-facing value demand while retaining checked/unchecked provenance long enough to
 * expand the right fixed inputs. Resolver inputs are checked regardless of how their output field
 * was reached. Field-checker inputs are unchecked and are introduced only by checked field demand;
 * type-checker object inputs are unchecked and are introduced when a checked object occurrence is
 * reached.
 */
fun Demand<SelectionForest>.successorDemandFromConstructionDemand(
    operation: SharedOperationContext<*>,
    possibleRootTypes: Set<ViaductSchema.Object> = emptySet(),
): SelectionForest {
    val context = SuccessorDemandContext(operation)
    val checkedDemand = checked.successorDemandWithChecks(context, checked = true)
    val rootTypeCheckerDemand =
        if (typeCheckDemanded) {
            possibleRootTypes.fixedTypeCheckerInputDemand(context)
        } else {
            selectionForestOf()
        }
    val uncheckedDemand =
        (unchecked + rootTypeCheckerDemand).successorDemandWithChecks(context, checked = false)
    val demand = checkedDemand + uncheckedDemand
    return demand + demand.liftParentSuccessorDemand(operation.world)
}

private class SuccessorDemandContext(
    val operation: SharedOperationContext<*>,
) {
    val expansionState = SuccessorExpansionState()
}

/** Cuts recursive input expansion at active field or type boundaries. */
private class SuccessorExpansionState {
    private val expandingBoundaries = mutableSetOf<SuccessorBoundary>()

    fun beginExpansion(boundary: SuccessorBoundary): Boolean = expandingBoundaries.add(boundary)

    fun endExpansion(boundary: SuccessorBoundary) {
        expandingBoundaries.remove(boundary)
    }
}

private data class SuccessorBoundary(
    val key: ObjectEngineResult.ObjectKey? = null,
    val kind: SuccessorBoundaryKind,
    val type: ViaductSchema.Object? = null,
) {
    init {
        require((key == null) != (type == null)) {
            "A successor boundary must identify exactly one field key or object type"
        }
    }
}

private enum class SuccessorBoundaryKind {
    RESOLVER,
    CHECKER,
    TYPE_CHECKER,
}

private fun SelectionForest.successorDemandWithChecks(
    context: SuccessorDemandContext,
    checked: Boolean,
): SelectionForest =
    flatMap { selection ->
        selection.requestedSuccessorDemand(context, checked) +
            selection.fixedSuccessorInputDemand(context, checked)
    }

private fun Selection.requestedSuccessorDemand(
    context: SuccessorDemandContext,
    checked: Boolean,
): SelectionForest {
    if (key.arguments.hasVariableTemplates()) return selectionForestOf()
    val nestedDemand =
        subselections.successorDemandWithChecks(context, checked) +
            if (checked) fixedTypeCheckerInputDemand(context) else selectionForestOf()
    return selectionForestOf(
        Selection.of(
            key = key,
            possibleTypes = possibleTypes,
            subselections = nestedDemand,
            inclusionCondition = inclusionCondition,
        ),
    )
}

private fun Selection.fixedTypeCheckerInputDemand(context: SuccessorDemandContext): SelectionForest {
    val outputType = key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
        ?: return selectionForestOf()
    return outputType.possibleObjectTypes.fixedTypeCheckerInputDemand(context)
}

private fun Set<ViaductSchema.Object>.fixedTypeCheckerInputDemand(context: SuccessorDemandContext): SelectionForest =
    flatMapToSelectionForest { type ->
        val checker = context.operation.world.resolverRegistry.typeChecker(type)
            ?: return@flatMapToSelectionForest selectionForestOf()
        require(checker.variables.isEmpty()) {
            "Grounded type checker ${checker.target.type.name} cannot declare variables"
        }
        val boundary = SuccessorBoundary(type = type, kind = SuccessorBoundaryKind.TYPE_CHECKER)
        if (!context.expansionState.beginExpansion(boundary)) {
            return@flatMapToSelectionForest selectionForestOf()
        }
        try {
            checker.objectFragment.successorDemandWithChecks(context, checked = false)
        } finally {
            context.expansionState.endExpansion(boundary)
        }
    }

private fun Selection.fixedSuccessorInputDemand(
    context: SuccessorDemandContext,
    checked: Boolean,
): SelectionForest =
    possibleTypes.flatMapToSelectionForest { possibleType ->
        val specializedKey = objectKey(possibleType)
        val key =
            ObjectEngineResult.ObjectKey.of(
                field = specializedKey.field,
                arguments =
                    specializedKey.arguments.successorArguments(
                        context.operation,
                        specializedKey.field,
                    ),
            )
        val arguments = key.arguments
        if (arguments == Arguments.Error) {
            selectionForestOf()
        } else {
            key.fixedResolverInputDemand(context, arguments) +
                if (checked) {
                    key.fixedCheckerInputDemand(context, arguments)
                } else {
                    selectionForestOf()
                }
        }
    }

private fun ObjectEngineResult.ObjectKey.fixedResolverInputDemand(
    context: SuccessorDemandContext,
    arguments: Arguments,
): SelectionForest {
    if (field !in context.operation.world.resolverRegistry) return selectionForestOf()
    val boundary = SuccessorBoundary(this, SuccessorBoundaryKind.RESOLVER)
    if (!context.expansionState.beginExpansion(boundary)) return selectionForestOf()
    return try {
        context.operation.world.resolverRegistry
            .resolver(field)
            .objectFragmentWithFromArguments(arguments)
            .successorDemandWithChecks(context, checked = true)
    } finally {
        context.expansionState.endExpansion(boundary)
    }
}

private fun ObjectEngineResult.ObjectKey.fixedCheckerInputDemand(
    context: SuccessorDemandContext,
    arguments: Arguments,
): SelectionForest {
    val checker =
        context.operation.world.resolverRegistry.fieldChecker(field)
            ?: return selectionForestOf()
    val boundary = SuccessorBoundary(this, SuccessorBoundaryKind.CHECKER)
    if (!context.expansionState.beginExpansion(boundary)) return selectionForestOf()
    return try {
        checker
            .objectFragmentWithFromArguments(arguments)
            .successorDemandWithChecks(context, checked = false)
    } finally {
        context.expansionState.endExpansion(boundary)
    }
}

private fun SelectionForest.successorDemandWithoutParentLifting(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.successorDemand(operation)
        val requestedDemand =
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = nestedDemand,
                    inclusionCondition = selection.inclusionCondition,
                ),
            )
        val resolverInputDemand =
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val specializedKey = selection.objectKey(possibleType)
                val key =
                    ObjectEngineResult.ObjectKey.of(
                        field = specializedKey.field,
                        arguments =
                            specializedKey.arguments.successorArguments(
                                operation,
                                specializedKey.field,
                            ),
                    )
                val arguments = key.arguments
                if (arguments == Arguments.Error || key.field !in operation.world.resolverRegistry) {
                    selectionForestOf()
                } else {
                    operation.world.resolverRegistry
                        .resolver(key.field)
                        .objectFragmentWithFromArguments(arguments)
                        .successorDemand(operation)
                }
            }
        (if (selection.key.arguments.hasVariableTemplates()) selectionForestOf() else requestedDemand) + resolverInputDemand
    }

/** Extends this demand with the paths needed to find every successor resolver boundary. */
fun SelectionForest.successorBoundaryDemand(operation: SharedOperationContext<*>): SelectionForest {
    val demand = successorBoundaryDemandWithoutParentLifting(operation)
    return demand + demand.liftParentSuccessorDemand(operation.world)
}

private fun SelectionForest.successorBoundaryDemandWithoutParentLifting(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val requested =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.successorBoundaryDemand(operation),
                inclusionCondition = selection.inclusionCondition,
            )

        (if (selection.key.arguments.hasVariableTemplates()) selectionForestOf() else selectionForestOf(requested)) + selection.successorInputBoundaries(operation)
    }

private fun Selection.successorInputBoundaries(operation: SharedOperationContext<*>): SelectionForest =
    possibleTypes.flatMapToSelectionForest { possibleType ->
        val specializedKey = objectKey(possibleType)
        val key =
            ObjectEngineResult.ObjectKey.of(
                field = specializedKey.field,
                arguments =
                    specializedKey.arguments.successorArguments(
                        operation,
                        specializedKey.field,
                    ),
            )
        val arguments = key.arguments
        if (arguments == Arguments.Error || key.field !in operation.world.resolverRegistry) {
            selectionForestOf()
        } else {
            operation.world.resolverRegistry
                .resolver(key.field)
                .objectFragmentWithFromArguments(arguments)
                .boundarySkeleton(operation)
                .successorBoundaryDemand(operation)
        }
    }

private fun SelectionForest.boundarySkeleton(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.boundarySkeleton(operation)
        val isResolverBoundary =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.requireField(selection.key.field.name)
                field in operation.world.resolverRegistry
            }

        if (isResolverBoundary || !nested.isEmpty()) {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = nested,
                    inclusionCondition = selection.inclusionCondition,
                ),
            )
        } else {
            selectionForestOf()
        }
    }

private fun Arguments.hasVariableTemplates(): Boolean = this !is Arguments.Ground && usedVariables().any(Arguments.Variable::isTemplate)

// Future provider occurrences have no bindings yet; only occurrence-owned variables can be grounded.
private fun Arguments.successorArguments(
    operation: SharedOperationContext<*>,
    field: ViaductSchema.Field,
): Arguments = if (hasVariableTemplates()) this else instantiateBindings(operation, field)

private data class KnownArgumentValue(val value: EngineInputData?)

private fun VariableDefinition.FromArgument.knownArgumentValue(arguments: Arguments): KnownArgumentValue? {
    if (arguments is Arguments.Resolved) return KnownArgumentValue(read(arguments))
    var value = arguments.fieldExpressions().getValue(argument.name)
    inputPath.forEach { field ->
        if (value is Arguments.Variable) return null
        if (value == null) return KnownArgumentValue(null)
        value = (value as Map<*, *>)[field.name]
    }
    return KnownArgumentValue(value)
}

private fun FieldValueResolver.objectFragmentWithFromArguments(arguments: Arguments): SelectionForest {
    val bindings =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromArgument)?.let {
                definition.knownArgumentValue(arguments)?.let { variable to it.value }
            }
        }.toMap()
    return objectFragment.substitute(bindings)
}

private fun FieldCheckerResolver.objectFragmentWithFromArguments(arguments: Arguments): SelectionForest {
    val bindings =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromArgument)?.let {
                definition.knownArgumentValue(arguments)?.let { variable to it.value }
            }
        }.toMap()
    return objectFragment.substitute(bindings)
}

private fun SelectionForest.substitute(bindings: Map<Arguments.Variable, EngineInputData?>): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.substituteTemplates(
                                selection.key.field,
                                bindings,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                inclusionCondition = selection.inclusionCondition,
                subselections = selection.subselections.substitute(bindings),
            ),
        )
    }
