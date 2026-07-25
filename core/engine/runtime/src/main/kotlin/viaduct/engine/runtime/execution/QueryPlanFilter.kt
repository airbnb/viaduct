package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.InlineFragment as GJInlineFragment
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
 * @param source is a detached selection set. When null, the plan's root selection set is used.
 * @param projectionType restricts [source] to one concrete runtime type. The filtered selection
 * set is then owned by that type.
 */
internal fun QueryPlan.filterTo(
    shape: KeyTree,
    context: QueryPlanFilterCtx,
    source: QueryPlan.SelectionSet? = null,
    projectionType: GraphQLObjectType? = null,
): QueryPlan {
    val effectiveSource = source ?: selectionSet
    val filtered = QueryPlanFilter(this, shape, context)
        .filter(effectiveSource, projectionType)
    val newVariablesResolvers = variablesResolvers
        .filter { vr -> vr.variableNames.any { it in filtered.activeVariableNames } }
    val newChildPlanIds = newVariablesResolvers
        .mapNotNull { it.requiredSelectionSet?.id }
        .distinct()

    return copy(
        selectionSet = filtered.selectionSet,
        fragments = QueryPlan.Fragments.empty,
        variablesResolvers = newVariablesResolvers,
        childPlanIds = newChildPlanIds,
        variableDefinitions = variableDefinitions.filter { it.name in filtered.activeVariableNames },
    )
}

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
    fun filter(
        source: QueryPlan.SelectionSet,
        projectionType: GraphQLObjectType?,
    ): FilteredQueryPlan {
        val filtered = projectSelectionSet(source, shape, projectionType)
        val activeVariableNames = filtered.selectionSet
            .toAstSelectionSet()
            .collectVariableReferences()
        return FilteredQueryPlan(
            selectionSet = filtered.selectionSet,
            activeVariableNames = activeVariableNames,
            // Field child plans stay attached to their fields. Top-level child plan IDs are rebuilt
            // from active variable resolvers after filtering, so there is no value to carry here.
        )
    }

    private fun projectSelectionSet(
        source: QueryPlan.SelectionSet,
        shape: KeyTree,
        projectionType: GraphQLObjectType? = null,
    ): FilteredSelectionSet {
        val selections = mutableListOf<QueryPlan.Selection>()
        val fieldsByType = shape.keysByType()

        val concreteSourceType = projectionType ?: (source.parentType as? GraphQLObjectType)
        if (concreteSourceType != null) {
            check(fieldsByType.keys.all { it == concreteSourceType }) {
                "Selection set on `${concreteSourceType.name}` cannot be projected to another concrete type"
            }
            val fields = fieldsByType[concreteSourceType].orEmpty()
            if (fields.isNotEmpty()) {
                val branch = projectForType(source, concreteSourceType, fields)
                selections += branch.selectionSet.selections
            }
        } else {
            for ((concreteType, fields) in fieldsByType) {
                if (fields.isEmpty()) continue
                val branch = projectForType(source, concreteType, fields)
                if (branch.selectionSet.selections.isEmpty()) continue

                val inlineAst = GJInlineFragment.newInlineFragment()
                    .typeCondition(GJTypeName(concreteType.name))
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
                parentType = concreteSourceType ?: source.parentType,
                selections = selections,
                enclosingVariableReferences = source.enclosingVariableReferences,
                conditionallyExcludedCoordinates = source.conditionallyExcludedCoordinates,
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
                parentType = concreteType,
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
                    ?.let { projectSelectionSet(it, childShape) }
                    ?.takeUnless {
                        !childShape.isEmpty() && it.selectionSet.selections.isEmpty()
                    }
                    ?: continue
            }
            selections += sourceField.copy(
                constraints = Constraints.Unconstrained.withDirectives(sourceField.field.directives),
                selectionSet = childProjection?.selectionSet,
                childPlans = field.childPlans,
                fieldTypeChildPlans = field.fieldTypeChildPlans,
                metadata = field.collectedFieldMetadata,
            )
        }

        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(concreteType, selections),
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
                childSelectionSet,
                childShape,
            )
        }
        if (
            childProjection != null &&
            !childShape.isEmpty() &&
            childProjection.selectionSet.selections.isEmpty()
        ) {
            return FilteredSelectionSet(
                selectionSet = QueryPlan.SelectionSet.empty(concreteType),
            )
        }
        return FilteredSelectionSet(
            selectionSet = QueryPlan.SelectionSet(
                concreteType,
                field.copy(
                    selectionSet = childProjection?.selectionSet,
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
    val activeVariableNames: Set<String>,
)

private class FilteredSelectionSet(
    val selectionSet: QueryPlan.SelectionSet,
)
