package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.SourceLocation
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.FieldMetadata
import viaduct.engine.runtime.execution.QueryPlan.FragmentDefinition
import viaduct.engine.runtime.execution.QueryPlan.FragmentSpread
import viaduct.engine.runtime.execution.QueryPlan.Fragments
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.engine.runtime.execution.constraints.Constraints.Resolution
import viaduct.utils.collections.MaskedSet

/** A planned field occurrence and its enclosing defer context. */
data class FieldDetails(val field: Field, val deferUsage: DeferUsage?)

/** Fields keyed by response name in encounter order. */
typealias CollectedFieldsMap = Map<String, CollectedField>

/** Unmerged occurrences of one response key, with shared derived views for execution. */
class CollectedField(
    val occurrences: List<FieldDetails>,
    private val schema: GraphQLSchema,
) {
    val responseKey: String get() = occurrences.first().field.resultKey
    val fieldName: String get() = occurrences.first().field.field.name
    val alias: String? get() = occurrences.first().field.field.alias
    val sourceLocation: SourceLocation get() = occurrences.first().field.field.sourceLocation ?: SourceLocation.EMPTY
    val childPlans: List<FieldChildPlan> get() = occurrences.first().field.childPlans
    val fieldTypeChildPlans: FieldTypeChildPlans get() = occurrences.first().field.fieldTypeChildPlans
    val collectedFieldMetadata: FieldMetadata? get() = occurrences.first().field.metadata

    val mergedField: MergedField by lazy {
        MergedField.newMergedField(occurrences.map { it.field.field }).build()
    }

    val selectionSet: SelectionSet? by lazy {
        val first = occurrences.first().field.selectionSet
        check(occurrences.all { (it.field.selectionSet == null) == (first == null) }) {
            "Cannot merge fields with different subselection flavors"
        }
        if (first == null) {
            null
        } else {
            occurrences.drop(1).fold(first) { acc, details -> acc.merge(details.field.selectionSet!!, schema) }
        }
    }

    fun withOccurrences(occurrences: List<FieldDetails>): CollectedField = CollectedField(occurrences, schema)

    internal fun toQueryPlanFields(): List<Field> = occurrences.map { it.field }

    override fun toString(): String = AstPrinter.printAst(mergedField.singleField)
}

object CollectFields {
    data class Result(
        val collectedFieldsMap: CollectedFieldsMap,
        val newDeferUsages: List<DeferUsage>,
    )

    /**
     * Apply the CollectFields algorithm to the given selection set
     * This method is "shallow", in that while it will traverse inline fragments and fragment spreads,
     * it will not traverse field subselections.
     * This method is "strict", in that if any selection cannot be collected, it will throw
     * an exception.
     */
    fun shallowStrictCollect(
        schema: GraphQLSchema,
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
    ): Result {
        val result = collect(
            State(
                schema = schema,
                acc = emptyMap(),
                pending = selectionSet.selections,
                spreadFragments = emptySet(),
                fragments = fragments,
                constraintsCtx = Constraints.Ctx(variables, MaskedSet(listOf(parentType))),
                parentType = parentType,
            ),
            fieldRssOriginFilteringKillSwitchEnabled = fieldRssOriginFilteringKillSwitchEnabled,
        )
        return Result(result.acc, emptyList())
    }

    /** models the state while collecting fields within a single SelectionSet */
    private data class State(
        val schema: GraphQLSchema,
        val acc: CollectedFieldsMap,
        val pending: List<Selection>,
        val spreadFragments: Set<String>,
        val fragments: Fragments,
        val constraintsCtx: Constraints.Ctx,
        val parentType: GraphQLObjectType,
    ) {
        fun fragmentDef(name: String): FragmentDefinition = requireNotNull(fragments[name]) { "Fragment `$name` is not defined" }

        fun constrainedTypes() = constraintsCtx.parentTypes?.toSet()
    }

    /**
     * Collect pending selections in State, according to the spec's definition for
     * CollectFields
     *  see https://spec.graphql.org/draft/#CollectFields()
     *
     * @see Constraints
     */
    private fun collect(
        state: State,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
    ): State {
        val visitedFragments = state.spreadFragments.toMutableSet()
        val acc = linkedMapOf<String, MutableList<FieldDetails>>()

        // the inner loop will both push and pop from the front of the queue
        // For example, we might handle an inline fragment by popping off the inline fragment
        // selection, and then pushing on the field selections of that fragment.
        // An ArrayDeque is a good data structure for this job, as it has constant-time reads/writes
        // when working at the front, and is more memory-efficient than a LinkedList
        val queue = ArrayDeque(state.pending)

        while (queue.isNotEmpty()) {
            val sel = queue.removeFirst()
            val resolution = sel.constraints.solve(state.constraintsCtx)

            when {
                resolution == Resolution.Drop -> continue

                resolution == Resolution.Unsolved ->
                    // We've encountered an Unsolved Constraints, indicating that we
                    // cannot completely collect this selection set.
                    throw IllegalStateException("Could not collect selection: $sel")

                // getting to this point implies that resolution == Resolution.Collect
                sel is Field -> {
                    val field =
                        sel.copy(
                            constraints = Constraints.Unconstrained,
                            childPlans = sel.childPlans.filter { fcp ->
                                val types = state.constrainedTypes()
                                val planParentType = fcp.queryPlanParentType
                                val planParentApplies =
                                    types == null ||
                                        types.contains(planParentType) ||
                                        planParentType.isRootType(state.schema)

                                // If killswitch enabled, fall back to more permissive filtering
                                // without field rss origin.
                                if (fieldRssOriginFilteringKillSwitchEnabled) {
                                    return@filter planParentApplies
                                }

                                val (originParentType, originFieldName) = fcp.originCoordinate
                                val originApplies =
                                    originFieldName == sel.field.name &&
                                        (types == null || types.any { it.name == originParentType })

                                planParentApplies && originApplies
                            },
                        )
                    acc
                        .getOrPut(field.resultKey) { mutableListOf() }
                        .add(FieldDetails(field, null))
                }

                sel is InlineFragment ->
                    // push all fragment fields onto the stack
                    queue.addAll(0, sel.selectionSet.selections)

                sel is FragmentSpread && sel.name in visitedFragments -> continue
                sel is FragmentSpread -> {
                    val def = state.fragmentDef(sel.name)
                    // push all fragment definition fields onto the stack
                    queue.addAll(0, def.selectionSet.selections)
                    visitedFragments += sel.name
                }

                else -> throw AssertionError("encountered unexpected state: $sel")
            }
        }

        return state.copy(
            acc = acc.mapValues { (_, fields) -> CollectedField(fields, state.schema) },
            pending = emptyList(),
            spreadFragments = visitedFragments
        )
    }

    private fun GraphQLCompositeType.isRootType(schema: GraphQLSchema) =
        this == schema.queryType ||
            this == schema.mutationType ||
            this == schema.subscriptionType
}
