package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.Directive
import graphql.language.DirectivesContainer
import graphql.language.SourceLocation
import graphql.language.VariableReference
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.FieldMetadata
import viaduct.engine.runtime.execution.QueryPlan.FragmentDefinition
import viaduct.engine.runtime.execution.QueryPlan.FragmentSpread
import viaduct.engine.runtime.execution.QueryPlan.Fragments
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.engine.runtime.execution.constraints.Constraints.Resolution
import viaduct.graphql.utils.rawValue
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

/**
 * Spec section 6.3.2
 *
 * Collects a selection set, applying conditional directives and type conditions.
 * Groups unmerged field occurrences by response key, preserving encounter order.
 */
fun interface CollectFields {
    data class Result(
        val collectedFieldsMap: CollectedFieldsMap,
        val newDeferUsages: List<DeferUsage>,
    )

    operator fun invoke(
        schema: EngineSchema,
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
        incrementalExecutionEnabled: Boolean,
    ): Result

    companion object {
        /** A default implementation of CollectFields */
        val default: CollectFields = DefaultCollectFields

        /** Returns a new collector with an execution-scoped result cache. */
        fun cached(underlying: CollectFields = default): CollectFields = CachedCollectFields(underlying)
    }
}

private object DefaultCollectFields : CollectFields {
    override fun invoke(
        schema: EngineSchema,
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
        incrementalExecutionEnabled: Boolean,
    ): CollectFields.Result {
        val result = collect(
            State(
                schema = schema.schema,
                acc = emptyMap(),
                pending = selectionSet.selections,
                spreadFragments = emptyMap(),
                fragments = fragments,
                constraintsCtx = Constraints.Ctx(variables, MaskedSet(listOf(parentType))),
                parentType = parentType,
            ),
            variables = variables,
            fieldRssOriginFilteringKillSwitchEnabled = fieldRssOriginFilteringKillSwitchEnabled,
            incrementalExecutionEnabled = incrementalExecutionEnabled,
        )
        return CollectFields.Result(result.acc, result.newDeferUsages)
    }

    /** models the state while collecting fields within a single SelectionSet */
    private data class State(
        val schema: GraphQLSchema,
        val acc: CollectedFieldsMap,
        val pending: List<Selection>,
        val spreadFragments: Map<String, Set<Defer?>>,
        val fragments: Fragments,
        val constraintsCtx: Constraints.Ctx,
        val parentType: GraphQLObjectType,
        val newDeferUsages: List<DeferUsage> = emptyList(),
    ) {
        fun fragmentDef(name: String): FragmentDefinition = requireNotNull(fragments[name]) { "Fragment `$name` is not defined" }

        fun constrainedTypes() = constraintsCtx.parentTypes?.toSet()
    }

    private data class PendingSelection(val selection: Selection, val deferUsage: DeferUsage?)

    /**
     * Collect pending selections in State, according to the spec's definition for
     * CollectFields
     *  see https://spec.graphql.org/draft/#CollectFields()
     *
     * @see Constraints
     */
    private fun collect(
        state: State,
        variables: CoercedVariables,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
        incrementalExecutionEnabled: Boolean,
    ): State {
        val visitedFragments = state.spreadFragments.mapValues { (_, defers) -> defers.toMutableSet() }.toMutableMap()
        val acc = linkedMapOf<String, MutableList<FieldDetails>>()
        val newDeferUsages = mutableListOf<DeferUsage>()

        // the inner loop will both push and pop from the front of the queue
        // For example, we might handle an inline fragment by popping off the inline fragment
        // selection, and then pushing on the field selections of that fragment.
        // An ArrayDeque is a good data structure for this job, as it has constant-time reads/writes
        // when working at the front, and is more memory-efficient than a LinkedList
        val queue = ArrayDeque(state.pending.map { PendingSelection(it, null) })

        while (queue.isNotEmpty()) {
            val (sel, deferUsage) = queue.removeFirst()
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
                        .add(FieldDetails(field, deferUsage))
                }

                sel is InlineFragment -> {
                    val fragmentUsage = fragmentDeferUsage(
                        sel,
                        deferUsage,
                        variables,
                        incrementalExecutionEnabled,
                    )
                    if (fragmentUsage != null && fragmentUsage !== deferUsage) newDeferUsages += fragmentUsage
                    queue.addAll(0, sel.selectionSet.selections.map { PendingSelection(it, fragmentUsage) })
                }

                sel is FragmentSpread -> {
                    val def = state.fragmentDef(sel.name)
                    val visitedDefers = visitedFragments.getOrPut(sel.name) { mutableSetOf() }
                    if (null in visitedDefers) continue
                    val fragmentUsage = fragmentDeferUsage(
                        sel,
                        deferUsage,
                        variables,
                        incrementalExecutionEnabled,
                    )
                    // A directive already visited for this fragment is skipped even under a different parent.
                    if (!visitedDefers.add(fragmentUsage?.defer)) continue
                    if (fragmentUsage != null && fragmentUsage !== deferUsage) newDeferUsages += fragmentUsage
                    queue.addAll(0, def.selectionSet.selections.map { PendingSelection(it, fragmentUsage) })
                }

                else -> throw AssertionError("encountered unexpected state: $sel")
            }
        }

        return state.copy(
            acc = acc.mapValues { (_, fields) -> CollectedField(fields, state.schema) },
            pending = emptyList(),
            spreadFragments = visitedFragments,
            newDeferUsages = newDeferUsages,
        )
    }

    private fun fragmentDeferUsage(
        selection: Selection,
        parentDeferUsage: DeferUsage?,
        variables: CoercedVariables,
        incrementalExecutionEnabled: Boolean,
    ): DeferUsage? {
        if (!incrementalExecutionEnabled) return null
        val directive = selection.deferDirective ?: return parentDeferUsage
        if (directive.argumentValue("if", variables) == false) return parentDeferUsage

        val label = directive.argumentValue("label", variables) as String?
        return DeferUsage(Defer(label, directive), parentDeferUsage)
    }

    private fun GraphQLCompositeType.isRootType(schema: GraphQLSchema) =
        this == schema.queryType ||
            this == schema.mutationType ||
            this == schema.subscriptionType
}

private val Selection.deferDirective: Directive?
    get() = when (this) {
        is InlineFragment -> inlineFragment?.deferDirective
        is FragmentSpread -> fragmentSpread?.deferDirective
        is Field -> null
    }

private val DirectivesContainer<*>.deferDirective: Directive?
    get() = getDirectives("defer")?.firstOrNull()

private fun Directive.argumentValue(
    name: String,
    variables: CoercedVariables
): Any? = getArgument(name)?.value?.rawValue(variables.toMap())

/**
 * Caches the results of field collection to optimize performance during execution.
 *
 * Both [FieldResolver] and [FieldCompleter] need to collect fields for the same objects.
 * Without caching, this work would be duplicated for every object in the response.
 *
 * The cache uses a specialized key that relies on **identity equality** for
 * its stable components ([GraphQLObjectType] and [QueryPlan.SelectionSet]). This is safe because:
 * 1. The [QueryPlan] (and its [QueryPlan.SelectionSet] nodes) is immutable and shared.
 * 2. Runtime variables that participate in field collection are included structurally because
 *    different child executions in the same request can run the same plan with different
 *    @skip/@include/@defer values.
 *
 * By avoiding expensive structural equality checks and repeated collection logic,
 * this cache significantly reduces overhead in the hot path of execution.
 */
private class CachedCollectFields(private val underlying: CollectFields) : CollectFields {
    private class CollectKey(
        val parentType: GraphQLObjectType,
        val selectionSet: SelectionSet,
        val variables: Map<String, Any?>,
        val incrementalExecutionEnabled: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollectKey) return false
            return parentType === other.parentType &&
                selectionSet === other.selectionSet &&
                variables == other.variables &&
                incrementalExecutionEnabled == other.incrementalExecutionEnabled
        }

        override fun hashCode(): Int {
            val a = System.identityHashCode(parentType)
            val b = System.identityHashCode(selectionSet)
            return ((31 * a + b) * 31 + variables.hashCode()) * 31 + incrementalExecutionEnabled.hashCode()
        }
    }

    private val map = ConcurrentHashMap<CollectKey, CollectFields.Result>()

    // The primary cache key needs collection-sensitive variable values, so we need to
    // discover the relevant variable names before we can query it. Keep that discovery
    // cached separately so cache hits do not rewalk fragments and constraints.
    private val collectionVariableNamesBySelectionSet = ConcurrentHashMap<CollectionVariableNamesKey, Set<String>>()

    override fun invoke(
        schema: EngineSchema,
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
        incrementalExecutionEnabled: Boolean,
    ): CollectFields.Result {
        val key = CollectKey(
            parentType,
            selectionSet,
            collectionVariableValues(selectionSet, variables, parentType, fragments),
            incrementalExecutionEnabled,
        )
        return map.computeIfAbsent(key) {
            underlying(
                schema,
                selectionSet,
                variables,
                parentType,
                fragments,
                fieldRssOriginFilteringKillSwitchEnabled,
                incrementalExecutionEnabled,
            )
        }
    }

    private fun collectionVariableValues(
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments
    ): Map<String, Any?> {
        val names = selectionSet.collectionVariableNames(parentType, fragments)
        return if (names.isEmpty()) emptyMap() else names.associateWith { variables.get(it) }
    }

    private class CollectionVariableNamesKey(
        val parentType: GraphQLObjectType,
        val selectionSet: SelectionSet,
        val fragments: Fragments
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollectionVariableNamesKey) return false
            return parentType === other.parentType &&
                selectionSet === other.selectionSet &&
                fragments === other.fragments
        }

        override fun hashCode(): Int {
            val a = System.identityHashCode(parentType)
            val b = System.identityHashCode(selectionSet)
            val c = System.identityHashCode(fragments)
            return (31 * a + b) * 31 + c
        }
    }

    /**
     * Runtime variable names that can affect field collection for [selectionSet].
     *
     * Query plans record variable references on selections and fragment definitions, but field
     * collection reads @skip/@include constraints and @defer arguments. Field argument variables
     * are resolved later by field execution and must not affect collection caching.
     */
    private fun SelectionSet.collectionVariableNames(
        parentType: GraphQLObjectType,
        fragments: Fragments
    ): Set<String> =
        collectionVariableNamesBySelectionSet.computeIfAbsent(CollectionVariableNamesKey(parentType, this, fragments)) {
            findCollectionVariableNames(parentType, fragments)
        }

    /**
     * Walk the same selection surface that [CollectFields] will inspect for [parentType].
     * Fields contribute their own variable references, but not references from their subselections.
     * Inline fragments and fragment spreads are expanded because their children are collected into
     * this same result. Each selection is solved with a variables-free [Constraints.Ctx] first, so
     * type-pruned branches and literal @skip/@include directives cannot add irrelevant variables to
     * the cache key.
     */
    private fun SelectionSet.findCollectionVariableNames(
        parentType: GraphQLObjectType,
        fragments: Fragments
    ): Set<String> =
        buildSet {
            addCollectionVariableNames(enclosingVariableReferences)
            forEachVariableReferencesVisibleToCollection(parentType, fragments) { references, deferDirective ->
                addCollectionVariableNames(references)
                for (name in listOf("if", "label")) {
                    val value = deferDirective?.getArgument(name)?.value
                    if (value is VariableReference) add(value.name)
                }
            }
        }

    private fun MutableSet<String>.addCollectionVariableNames(references: List<SelectionVariableReference>) {
        references.forEach { reference ->
            if (reference.kind == SelectionVariableReference.Kind.CONDITIONAL_DIRECTIVE) {
                add(reference.name)
            }
        }
    }

    private fun SelectionSet.forEachVariableReferencesVisibleToCollection(
        parentType: GraphQLObjectType,
        fragments: Fragments,
        visit: (List<SelectionVariableReference>, Directive?) -> Unit
    ) {
        val ctx = Constraints.Ctx(variables = null, parentTypes = MaskedSet(listOf(parentType)))
        val visitedFragments = mutableSetOf<String>()
        val queue = ArrayDeque(selections)

        while (queue.isNotEmpty()) {
            when (val selection = queue.removeFirst()) {
                is Field -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences, null)
                }

                is InlineFragment -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences, selection.deferDirective)
                    queue.addAll(0, selection.selectionSet.selections)
                }

                is FragmentSpread -> {
                    if (selection.isDroppedFor(ctx)) continue
                    visit(selection.variableReferences, selection.deferDirective)
                    if (visitedFragments.add(selection.name)) {
                        val fragmentDefinition = requireNotNull(fragments[selection.name]) { "Fragment `${selection.name}` is not defined" }
                        visit(fragmentDefinition.variableReferences, null)
                        queue.addAll(0, fragmentDefinition.selectionSet.selections)
                    }
                }
            }
        }
    }

    private fun Selection.isDroppedFor(ctx: Constraints.Ctx): Boolean = constraints.solve(ctx) == Constraints.Resolution.Drop
}
