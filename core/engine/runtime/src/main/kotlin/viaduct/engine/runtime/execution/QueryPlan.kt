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
 * @property childPlans Child QueryPlan objects resolved before any selections in this plan.
 * @property astSelectionSet The original graphql-java AST selection set this plan was built from.
 * @property attribution Execution attribution for tracing and instrumentation.
 * @property executionCondition Condition that controls whether this plan executes at runtime.
 * @property variableDefinitions Pre-computed variable definitions for this plan.
 */
data class QueryPlan(
    val selectionSet: SelectionSet,
    val fragments: Fragments,
    val variablesResolvers: List<VariablesResolver>,
    val parentType: GraphQLOutputType,
    val childPlans: List<QueryPlan>,
    val astSelectionSet: GJSelectionSet,
    val attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
    val executionCondition: QueryPlanExecutionCondition,
    val variableDefinitions: List<VariableDefinition>,
) {
    /**
     * Configuration for building a QueryPlan.
     *
     * @property query The query text used as part of the cache key. For top-level operations
     *   this is the client's query string. For [buildFromSelections] and [buildFromParsedSelections],
     *   this is computed internally from the selection set — callers can omit it.
     * @property schema GraphQL schema used for type verification and field resolution.
     * @property registry Registry for looking up RequiredSelectionSets declared by resolvers and checkers.
     * @property executeAccessChecksInModstrat Whether access checks should be executed in modstrat.
     *   Affects which RequiredSelectionSets are included in the plan.
     * @property dispatcherRegistry Registry for looking up resolver and checker dispatchers.
     * @property executionCondition Condition under which QueryPlans built with these parameters
     *   should execute at runtime. Defaults to always execute.
     */
    data class Parameters(
        val query: String = "",
        val schema: ViaductSchema,
        val registry: RequiredSelectionSetRegistry,
        val executeAccessChecksInModstrat: Boolean,
        val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
        val executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE
    )

    /**
     * A Selection models any kind of element that may appear in a QueryPlan SelectionSet.
     *
     * Selection comes in some of the same flavors as graphql-java's [graphql.language.Selection],
     * though with the significant inclusion of CollectedField.
     */
    sealed interface Selection {
        val constraints: Constraints
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
        val childPlans: List<QueryPlan>,
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
        val childPlans: List<QueryPlan>,
        val fieldTypeChildPlans: Map<GraphQLObjectType, Lazy<List<QueryPlan>>>,
        val metadata: FieldMetadata? = FieldMetadata.empty,
    ) : Selection {
        override fun toString(): String = AstPrinter.printAst(field)
    }

    data class FragmentSpread(
        val name: String,
        override val constraints: Constraints
    ) : Selection

    data class InlineFragment(
        val selectionSet: SelectionSet,
        override val constraints: Constraints
    ) : Selection

    data class FragmentDefinition(val selectionSet: SelectionSet, val gjDef: GJFragmentDefinition, val childPlans: List<QueryPlan>)

    data class Fragments(val map: Map<String, FragmentDefinition>) : Map<String, FragmentDefinition> by map {
        operator fun plus(other: Fragments): Fragments = copy(map + other.map)

        operator fun plus(entry: Pair<String, FragmentDefinition>): Fragments = copy(map + entry)

        companion object {
            val empty: Fragments = Fragments(emptyMap())
        }
    }

    data class SelectionSet(val selections: List<Selection>) {
        constructor(vararg selections: Selection) : this(listOf(*selections))

        operator fun plus(selection: Selection): SelectionSet = copy(selections = selections + selection)

        companion object {
            val empty: SelectionSet = SelectionSet(emptyList())
        }
    }

    /**
     * Metadata of the field.
     * @property resolverCoordinate This is the field coordinate points the resolver which resolves the current field
     */
    data class FieldMetadata(
        val resolverCoordinate: Coordinate?
    ) {
        companion object {
            val empty: FieldMetadata = FieldMetadata(null)
        }
    }
}
