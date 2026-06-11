package viaduct.engine.runtime.execution

import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.SourceLocation
import graphql.language.VariableDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.constraints.Constraints

/**
 * QueryPlan is an intermediate representation of a GraphQL selection set.
 * It includes models of viaduct-specific concepts, including required selection sets
 * and their variables.
 *
 * @property selectionSet The collected fields and selections for this plan level.
 * @property fragments Named fragment definitions available during plan execution.
 * @property variablesResolvers Resolvers that produce variable values at execution time.
 * @property parentType The GraphQL type that owns the fields in this plan.
 * @property childPlanIds RequiredSelectionSet plans resolved before any selections in this plan.
 * @property baseIndex Index over this plan's eager child plans. [index] adds this plan itself.
 * @property astSelectionSet The original graphql-java AST selection set this plan was built from.
 * @property attribution Execution attribution for tracing and instrumentation.
 * @property executionCondition Condition that controls whether this plan executes at runtime.
 * @property variableDefinitions Pre-computed variable definitions for this plan.
 * @property requiredSelectionSetId The id of the RequiredSelectionSet instance that produced this child plan.
 */
data class QueryPlan(
    val selectionSet: SelectionSet,
    val fragments: Fragments,
    val variablesResolvers: List<VariablesResolver>,
    val parentType: GraphQLOutputType,
    val childPlanIds: List<RequiredSelectionSet.Id>,
    private val baseIndex: QueryPlanIndex,
    val astSelectionSet: GJSelectionSet,
    val attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
    val executionCondition: QueryPlanExecutionCondition,
    val variableDefinitions: List<VariableDefinition>,
    val requiredSelectionSetId: RequiredSelectionSet.Id? = null,
) {
    /** Index over this plan and its eager child plans. */
    val index: QueryPlanIndex =
        if (requiredSelectionSetId == null) {
            baseIndex
        } else {
            Index.Builder<RequiredSelectionSet.Id, QueryPlan>()
                .add(baseIndex)
                .add(requiredSelectionSetId, this)
                .build()
        }

    /**
     * Configuration for building a QueryPlan.
     *
     * @property query The query text used as part of the cache key. For top-level operations
     *   this is the client's query string. For [buildFromSelections] and [buildFromParsedSelections],
     *   this is computed internally from the selection set — callers can omit it.
     * @property schema GraphQL schema used for type verification and field resolution.
     * @property registry Registry for looking up RequiredSelectionSets declared by resolvers and checkers.
     * @property dispatcherRegistry Registry for looking up resolver and checker dispatchers.
     * @property executionCondition Condition under which QueryPlans built with these parameters
     *   should execute at runtime. Defaults to always execute.
     */
    data class Parameters(
        val query: String = "",
        val schema: ViaductSchema,
        val registry: RequiredSelectionSetRegistry,
        val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
        val executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE
    )

    /**
     * A variable reference found while building the query plan.
     *
     * Query planning records where each variable was referenced so runtime code can answer
     * narrower questions without rewalking the graphql-java AST. For example, [CollectCache]
     * only cares about variables used by conditional directives, while child-plan construction
     * also needs variables used in field arguments and other directives.
     */
    data class SelectionVariableReference(
        val name: String,
        val kind: Kind
    ) {
        enum class Kind {
            FIELD_ARGUMENT,
            CONDITIONAL_DIRECTIVE,
            DIRECTIVE
        }
    }

    /**
     * A Selection models any kind of element that may appear in a QueryPlan SelectionSet.
     *
     * Selection comes in some of the same flavors as graphql-java's [graphql.language.Selection],
     * though with the significant inclusion of CollectedField.
     */
    sealed interface Selection {
        val constraints: Constraints
        val variableReferences: List<SelectionVariableReference> get() = emptyList()
    }

    /**
     * A CollectedField is the result of applying the CollectFields algorithm.
     *
     * It represents a merged and normalized selection within a selection set, and has
     * no unresolved constraints like unapplied conditional directives.
     *
     * A CollectedField will always be executed.
     */
    data class CollectedField(
        val responseKey: String,
        val selectionSet: SelectionSet?,
        val mergedField: MergedField,
        val childPlans: List<FieldChildPlan>,
        val fieldTypeChildPlans: Map<GraphQLObjectType, Lazy<List<QueryPlan>>>,
        val collectedFieldMetadata: FieldMetadata? = FieldMetadata.empty,
    ) : Selection {
        override val constraints: Constraints get() = Constraints.Unconstrained

        val sourceLocation: SourceLocation get() = mergedField.singleField.sourceLocation ?: SourceLocation.EMPTY
        val fieldName: String get() = mergedField.name
        val alias: String? get() = mergedField.singleField.alias

        override fun toString(): String = AstPrinter.printAst(mergedField.singleField)
    }

    /**
     * [Selection] also has representations similar to graphql-java's [graphql.language.Selection] classes.
     *
     * These selections have not been collected yet and may be subject to [Constraints]
     * that determine if/how they get collected.
     *
     * @param fieldTypeChildPlans Map from possible concrete field type to child plans. The value is lazily computed
     *  because across executions of a single operation, polymorphic fields typically resolve to just one concrete
     *  type and the other child plans will be unused.
     */
    data class Field(
        val resultKey: String,
        override val constraints: Constraints,
        val field: GJField,
        val selectionSet: SelectionSet?,
        val childPlans: List<FieldChildPlan>,
        val fieldTypeChildPlans: Map<GraphQLObjectType, Lazy<List<QueryPlan>>>,
        val metadata: FieldMetadata? = FieldMetadata.empty,
        override val variableReferences: List<SelectionVariableReference> = emptyList(),
    ) : Selection {
        override fun toString(): String = AstPrinter.printAst(field)
    }

    data class FragmentSpread(
        val name: String,
        override val constraints: Constraints,
        override val variableReferences: List<SelectionVariableReference> = emptyList()
    ) : Selection

    data class InlineFragment(
        val selectionSet: SelectionSet,
        override val constraints: Constraints,
        override val variableReferences: List<SelectionVariableReference> = emptyList()
    ) : Selection

    /**
     * Planned fragment definition.
     *
     * @property index Index over child plans reachable from this fragment definition body.
     *   Spread-site directive variable plans are not included here because they are specific to
     *   each fragment spread.
     */
    data class FragmentDefinition(
        val selectionSet: SelectionSet,
        val gjDef: GJFragmentDefinition,
        val childPlanIds: List<RequiredSelectionSet.Id>,
        val variableReferences: List<SelectionVariableReference> = emptyList(),
        val index: QueryPlanIndex,
    )

    data class Fragments(val map: Map<String, FragmentDefinition>) : Map<String, FragmentDefinition> by map {
        operator fun plus(other: Fragments): Fragments = copy(map + other.map)

        operator fun plus(entry: Pair<String, FragmentDefinition>): Fragments = copy(map + entry)

        companion object {
            val empty: Fragments = Fragments(emptyMap())
        }
    }

    /**
     * A set of query-plan selections at one execution level.
     *
     * [enclosingVariableReferences] are variable references from the selection that owns this
     * selection set, rather than from one of the child selections. Field collection can be invoked
     * directly on a nested selection set, such as the body of `user @include(if: $show) { id }`.
     * Carrying the enclosing references with the child selection set keeps that boundary visible
     * without forcing collection to know which field or inline fragment led to the selection set.
     */
    class SelectionSet private constructor(
        val selections: List<Selection>,
        val enclosingVariableReferences: List<SelectionVariableReference>
    ) {
        constructor(selections: List<Selection>) : this(selections, emptyList())

        constructor(vararg selections: Selection) : this(listOf(*selections))

        operator fun plus(selection: Selection): SelectionSet = SelectionSet(selections + selection, enclosingVariableReferences)

        internal fun withEnclosingVariableReferences(enclosingVariableReferences: List<SelectionVariableReference>): SelectionSet {
            return SelectionSet(selections, enclosingVariableReferences)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SelectionSet) return false
            return selections == other.selections
        }

        override fun hashCode(): Int = selections.hashCode()

        override fun toString(): String = "SelectionSet(selections=$selections)"

        companion object {
            val empty: SelectionSet = SelectionSet(emptyList())
        }
    }

    /**
     * Metadata of the field.
     * @property resolvedByCoordinate The field coordinate for the resolver that produced the current object scope.
     * Set to the field's own coordinate when that field has a resolver, otherwise propagated from the parent
     * selection. Used by observability to attribute trivial field fetches to the resolver that created the
     * parent object. Not used to decide whether a field itself is selectively resolved — that is derived at
     * runtime from [DispatcherRegistry] using the concrete object type, since resolvers are only bound to
     * concrete-object field coordinates.
     */
    data class FieldMetadata(
        val resolvedByCoordinate: Coordinate?,
    ) {
        companion object {
            val empty: FieldMetadata = FieldMetadata(null)
        }
    }
}
