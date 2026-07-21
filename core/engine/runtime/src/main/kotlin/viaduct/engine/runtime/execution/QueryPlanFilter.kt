package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.language.Field as GJField
import graphql.language.FragmentSpread as GJFragmentSpread
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.Selection as GJSelection
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName as GJTypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.engine.runtime.mat.KeyTree
import viaduct.graphql.utils.collectVariableReferences
import viaduct.utils.collections.MaskedSet

/**
 * Builds a plan from an existing plan after keeping only fields present in [shape].
 *
 * The projection is performed independently for every concrete object type in [shape]. This is
 * important for abstract output types: a selection owned on one implementation must not be
 * widened to every implementation of the interface or union.
 *
 * @param shape is the field shape to keep.
 * @param source is a detached selection set and the GraphQL type that owns its fields. When null,
 * the plan's root selection set and parent type are used.
 */
internal fun QueryPlan.filterTo(
    shape: KeyTree,
    context: QueryPlanFilterCtx,
    source: TypedSelectionSet? = null,
): QueryPlan {
    val effectiveSource = source ?: TypedSelectionSet(
        selectionSet = selectionSet,
        parentType = requireNotNull(GraphQLTypeUtil.unwrapAll(parentType) as? GraphQLCompositeType) {
            "QueryPlan parent type `$parentType` is not composite"
        },
    )
    val filtered = QueryPlanFilter(this, shape, context)
        .filter(effectiveSource)
    val newVariablesResolvers = variablesResolvers
        .filter { vr -> vr.variableNames.any { it in filtered.activeVariableNames } }
    val newChildPlanIds = newVariablesResolvers
        .mapNotNull { it.requiredSelectionSet?.id }
        .distinct()

    return copy(
        selectionSet = filtered.selectionSet,
        fragments = QueryPlan.Fragments.empty,
        variablesResolvers = newVariablesResolvers,
        parentType = effectiveSource.parentType,
        childPlanIds = newChildPlanIds,
        astSelectionSet = filtered.astSelectionSet,
        variableDefinitions = variableDefinitions.filter { it.name in filtered.activeVariableNames },
    )
}

internal data class TypedSelectionSet(
    val selectionSet: QueryPlan.SelectionSet,
    val parentType: GraphQLCompositeType,
)

internal data class QueryPlanFilterCtx(
    val schema: GraphQLSchema,
    val variables: CoercedVariables = CoercedVariables.emptyVariables(),
    val graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    val locale: Locale = Locale.getDefault(),
    val fieldRssOriginFilteringKillSwitchEnabled: Boolean = true,
    val collectCache: CollectCache = CollectCache(),
) {
    constructor(parameters: ExecutionParameters) : this(
        schema = parameters.graphQLSchema,
        variables = parameters.coercedVariables,
        graphQLContext = parameters.executionContext.graphQLContext,
        locale = parameters.executionContext.locale,
        fieldRssOriginFilteringKillSwitchEnabled =
            parameters.engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled,
        collectCache = parameters.constants.collectCache,
    )
}

private class QueryPlanFilter(
    private val sourcePlan: QueryPlan,
    private val shape: KeyTree,
    private val context: QueryPlanFilterCtx,
) {
    fun filter(source: TypedSelectionSet): FilteredQueryPlan {
        val filtered = projectSelectionSet(source, shape)
        val activeVariableNames = filtered.astSelectionSet.collectVariableReferences()
        return FilteredQueryPlan(
            selectionSet = filtered.selectionSet,
            astSelectionSet = filtered.astSelectionSet,
            activeVariableNames = activeVariableNames,
            // Field child plans stay attached to their fields. Top-level child plan IDs are rebuilt
            // from active variable resolvers after filtering, so there is no value to carry here.
        )
    }

    private fun projectSelectionSet(
        source: TypedSelectionSet,
        shape: KeyTree,
    ): FilteredSelectionSet {
        val selections = mutableListOf<QueryPlan.Selection>()
        val fieldsByType = shape.keysByType()

        if (source.parentType is GraphQLObjectType) {
            check(fieldsByType.keys.all { it == source.parentType }) {
                "Selection set on `${source.parentType.name}` cannot be projected to another concrete type"
            }
            val fields = fieldsByType[source.parentType].orEmpty()
            if (fields.isNotEmpty()) {
                val branch = projectForType(source.selectionSet, source.parentType, fields)
                selections += branch.selectionSet.selections
            }
        } else {
            for ((concreteType, fields) in fieldsByType) {
                if (fields.isEmpty()) continue
                val branch = projectForType(source.selectionSet, concreteType, fields)
                if (branch.selectionSet.selections.isEmpty()) continue

                val inlineAst = GJInlineFragment.newInlineFragment()
                    .typeCondition(GJTypeName(concreteType.name))
                    .selectionSet(branch.astSelectionSet)
                    .build()
                selections += QueryPlan.InlineFragment(
                    selectionSet = branch.selectionSet,
                    constraints = Constraints(emptyList(), listOf(concreteType)),
                    inlineFragment = inlineAst,
                )
            }
        }

        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(
                selections = selections,
                enclosingVariableReferences = source.selectionSet.enclosingVariableReferences,
                conditionallyExcludedCoordinates = source.selectionSet.conditionallyExcludedCoordinates,
            ),
        )
    }

    private fun projectForType(
        selectionSet: QueryPlan.SelectionSet,
        concreteType: GraphQLObjectType,
        fields: Map<ObjectEngineResult.Key, KeyTree>,
    ): FilteredSelectionSet {
        val fieldSources = activeFieldSourcesByResponseKey(selectionSet, concreteType)
        val collected = context.collectCache.collect(
            schema = context.schema,
            selectionSet = selectionSet,
            variables = context.variables,
            parentType = concreteType,
            fragments = sourcePlan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled = context.fieldRssOriginFilteringKillSwitchEnabled,
        )
        val selections = mutableListOf<QueryPlan.Selection>()

        for (selection in collected.selections) {
            val field = selection as QueryPlan.CollectedField
            val resolvedField = field.resolveField(
                schema = context.schema,
                parentType = concreteType,
                variables = context.variables,
                graphQLContext = context.graphQLContext,
                locale = context.locale,
            )
            val childShape = fields[field.oerKey(resolvedField.arguments)] ?: continue
            val childType = GraphQLTypeUtil.unwrapAll(resolvedField.fieldDefinition.type) as? GraphQLCompositeType
            require(childShape.isEmpty() || childType != null) {
                "Field `${concreteType.name}.${field.fieldName}` has child selections but is not composite"
            }
            val sources = fieldSources.getValue(field.responseKey)
            val collectedSource = sources.singleOrNull() as? QueryPlan.CollectedField
            val projection = if (collectedSource == null) {
                projectFieldOccurrences(
                    field = field,
                    sourceFields = sources.map { it as QueryPlan.Field },
                    concreteType = concreteType,
                    childShape = childShape,
                    childType = childType,
                )
            } else {
                projectCollectedField(
                    field = collectedSource,
                    concreteType = concreteType,
                    childShape = childShape,
                    childType = childType,
                )
            }
            selections += projection.selectionSet.selections
        }

        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(
                selections = selections,
                enclosingVariableReferences = selectionSet.enclosingVariableReferences,
                conditionallyExcludedCoordinates = selectionSet.conditionallyExcludedCoordinates,
            ),
        )
    }

    private fun projectFieldOccurrences(
        field: QueryPlan.CollectedField,
        sourceFields: List<QueryPlan.Field>,
        concreteType: GraphQLObjectType,
        childShape: KeyTree,
        childType: GraphQLCompositeType?,
    ): FilteredSelectionSet {
        val selections = mutableListOf<QueryPlan.Selection>()

        for (sourceField in sourceFields) {
            val childProjection = if (childType == null) {
                null
            } else {
                sourceField.selectionSet
                    ?.let {
                        projectSelectionSet(
                            TypedSelectionSet(selectionSet = it, parentType = childType),
                            childShape,
                        )
                    }
                    ?.takeUnless {
                        !childShape.isEmpty() && it.selectionSet.selections.isEmpty()
                    }
                    ?: continue
            }
            val projectedField = sourceField.field.withSelectionSet(childProjection?.astSelectionSet)
            selections += sourceField.copy(
                constraints = Constraints.Unconstrained.withDirectives(projectedField.directives),
                field = projectedField,
                selectionSet = childProjection?.selectionSet,
                childPlans = field.childPlans,
                fieldTypeChildPlans = field.fieldTypeChildPlans,
                metadata = field.collectedFieldMetadata,
            )
        }

        check(selections.isNotEmpty()) {
            "Projection omitted requested child selections for `${concreteType.name}.${field.fieldName}`"
        }
        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(selections),
        )
    }

    private fun projectCollectedField(
        field: QueryPlan.CollectedField,
        concreteType: GraphQLObjectType,
        childShape: KeyTree,
        childType: GraphQLCompositeType?,
    ): FilteredSelectionSet {
        val childProjection = if (childType == null) {
            null
        } else {
            val childSelectionSet = requireNotNull(field.selectionSet) {
                "Composite field `${concreteType.name}.${field.fieldName}` has no selection set"
            }
            projectSelectionSet(
                TypedSelectionSet(selectionSet = childSelectionSet, parentType = childType),
                childShape,
            ).also {
                check(childShape.isEmpty() || it.selectionSet.selections.isNotEmpty()) {
                    "Projection omitted requested child selections for `${concreteType.name}.${field.fieldName}`"
                }
            }
        }
        val mergedField = when (childProjection) {
            null -> field.mergedField.withoutSelectionSet()
            else -> field.mergedField.withSelectionSet(childProjection.astSelectionSet)
        }
        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(
                field.copy(
                    selectionSet = childProjection?.selectionSet,
                    mergedField = mergedField,
                )
            ),
        )
    }

    private fun activeFieldSourcesByResponseKey(
        selectionSet: QueryPlan.SelectionSet,
        concreteType: GraphQLObjectType,
    ): Map<String, List<QueryPlan.Selection>> {
        val result = linkedMapOf<String, MutableList<QueryPlan.Selection>>()
        val pending = ArrayDeque(selectionSet.selections)
        val visitedFragments = mutableSetOf<String>()
        val constraintsCtx = Constraints.Ctx(context.variables, MaskedSet(listOf(concreteType)))

        while (pending.isNotEmpty()) {
            val selection = pending.removeFirst()
            when (selection.constraints.solve(constraintsCtx)) {
                Constraints.Resolution.Drop -> continue
                Constraints.Resolution.Unsolved -> error("Could not project selection: $selection")
                Constraints.Resolution.Collect -> Unit
            }

            when (selection) {
                is QueryPlan.CollectedField ->
                    result[selection.responseKey] = mutableListOf(selection)
                is QueryPlan.Field -> {
                    val sources = result.getOrPut(selection.resultKey) { mutableListOf() }
                    if (sources.firstOrNull() !is QueryPlan.CollectedField) sources += selection
                }
                is QueryPlan.InlineFragment -> pending.addAll(0, selection.selectionSet.selections)
                is QueryPlan.FragmentSpread -> {
                    if (!visitedFragments.add(selection.name)) continue
                    pending.addAll(0, sourcePlan.fragments.getValue(selection.name).selectionSet.selections)
                }
            }
        }

        return result
    }
}

private data class FilteredQueryPlan(
    val selectionSet: QueryPlan.SelectionSet,
    val astSelectionSet: GJSelectionSet,
    val activeVariableNames: Set<String>,
)

private class FilteredSelectionSet(
    val selectionSet: QueryPlan.SelectionSet,
) {
    val astSelectionSet: GJSelectionSet = selectionSet.toProjectedAstSelectionSet()
}

// Projection synchronizes each selection's AST as it is built, so assembling the result only
// needs the AST nodes at this level.
private fun QueryPlan.SelectionSet.toProjectedAstSelectionSet(): GJSelectionSet =
    GJSelectionSet.newSelectionSet()
        .selections(selections.flatMap { it.toProjectedAstSelections() })
        .build()

private fun QueryPlan.Selection.toProjectedAstSelections(): List<GJSelection<*>> =
    when (this) {
        is QueryPlan.CollectedField -> mergedField.fields
        is QueryPlan.Field -> listOf(field)
        is QueryPlan.InlineFragment -> listOf(
            inlineFragment
                ?: GJInlineFragment.newInlineFragment()
                    .selectionSet(selectionSet.toProjectedAstSelectionSet())
                    .build()
        )
        is QueryPlan.FragmentSpread ->
            listOf(fragmentSpread ?: GJFragmentSpread.newFragmentSpread(name).build())
    }

private fun MergedField.withSelectionSet(selectionSet: GJSelectionSet): MergedField = withTransformedFields { it.withSelectionSet(selectionSet) }

private fun MergedField.withoutSelectionSet(): MergedField = withTransformedFields { field -> field.withSelectionSet(null) }

private fun MergedField.withTransformedFields(transform: (GJField) -> GJField): MergedField =
    MergedField.newMergedField(fields.map(transform))
        .addDeferredExecutions(deferredExecutions)
        .build()

private fun GJField.withSelectionSet(selectionSet: GJSelectionSet?): GJField = transform { it.selectionSet(selectionSet) }
