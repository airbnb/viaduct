package viaduct.engine.runtime.execution

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.FragmentSpread as GJFragmentSpread
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.NodeUtil
import graphql.language.OperationDefinition
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.SourceLocation
import graphql.language.VariableDefinition
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import java.util.concurrent.Executors
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.future.await
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.QueryPlanExecutionCondition
import viaduct.engine.api.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.graphql.utils.asNamedElement
import viaduct.graphql.utils.collectVariableDefinitions
import viaduct.graphql.utils.collectVariableReferences
import viaduct.utils.collections.MaskedSet

/**
 * QueryPlan is an intermediate representation of a GraphQL selection set.
 * It includes models of viaduct-specific concepts, including required selection sets
 * and their variables.
 *
 * @param childPlans child QueryPlan objects. These will be resolved before any
 * selections in this QueryPlan are resolved.
 * @param variableDefinitions Pre-computed variable definitions for this plan.
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
     * @property schema GraphQL schema used for type verification and field resolution
     */
    data class Parameters(
        val query: String,
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
     * no unresolved constraints like unapplied conditional directives, and will always be executed.
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
        val sourceLocation: SourceLocation get() = mergedField.singleField.sourceLocation
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

    data class FragmentDefinition(val selectionSet: SelectionSet, val gjDef: GJFragmentDefinition)

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

    companion object {
        private val queryPlanBuilderExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
        )
            // ensure that threads in this pool are created as daemons, otherwise this thread pool
            // prevents graceful shutdown in ExecutionBenchmark
            { runnable ->
                Executors.defaultThreadFactory().newThread(runnable).also { it.setDaemon(true) }
            }

        private val queryPlanCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .executor(queryPlanBuilderExecutor)
            .buildAsync<QueryPlanCacheKey, QueryPlan>()

        private data class QueryPlanCacheKey(val documentText: String, val documentKey: DocumentKey, val schemaHashCode: Int, val executeAccessChecksInModstrat: Boolean)

        fun resetCache() {
            queryPlanCache.synchronous().invalidateAll()
        }

        internal val cacheSize: Long get() = queryPlanCache.synchronous().estimatedSize()

        /**
         * Builds a [QueryPlan] from a GraphQL [Document].
         *
         * @param parameters The parameters containing the schema.
         * @param document The GraphQL document.
         * @param documentKey A pointer into [document] describing the element to build a QueryPlan around
         * @return A new [QueryPlan] instance.
         */
        suspend fun build(
            parameters: Parameters,
            document: Document,
            documentKey: DocumentKey? = null,
            useCache: Boolean = true,
            attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        ): QueryPlan {
            val fragmentsByName = NodeUtil.getFragmentsByName(document)
            val operations = document.getDefinitionsOfType(OperationDefinition::class.java)

            val docKey = documentKey
                ?: operations.firstOrNull()?.let { DocumentKey.Operation(it.name) }
                ?: document.getFirstDefinitionOfType(GJFragmentDefinition::class.java).getOrNull()?.let { DocumentKey.Fragment(it.name) }
                ?: throw IllegalStateException("document contains no fragment or operation definitions")

            val (selectionSet, parentType) = when (val key = docKey) {
                is DocumentKey.Operation -> {
                    val maybeOp =
                        if (key.name != null) {
                            document.getOperationDefinition(key.name).getOrNull()
                        } else {
                            document.getFirstDefinitionOfType(OperationDefinition::class.java).getOrNull()
                        }
                    val op = checkNotNull(maybeOp) {
                        "Operation `${key.name}` not found in document"
                    }
                    op.selectionSet to getParentTypeFromDefinition(parameters, op)
                }

                is DocumentKey.Fragment -> {
                    val frag = checkNotNull(fragmentsByName[key.name]) {
                        "Fragment `${key.name}` not found in document"
                    }
                    frag.selectionSet to getParentTypeFromDefinition(parameters, frag)
                }
            }

            return build(parameters, selectionSet, docKey, parentType, fragmentsByName, useCache, attribution)
        }

        /**
         * Determines the parent GraphQL type based on the given definition.
         *
         * @param parameters The parameters containing the schema.
         * @param definition The operation or fragment definition.
         * @return The parent [GraphQLCompositeType].
         */
        private fun getParentTypeFromDefinition(
            parameters: Parameters,
            definition: Any,
        ): GraphQLCompositeType {
            return when (definition) {
                is OperationDefinition -> when (definition.operation) {
                    OperationDefinition.Operation.QUERY -> parameters.schema.schema.queryType
                    OperationDefinition.Operation.MUTATION -> parameters.schema.schema.mutationType
                    OperationDefinition.Operation.SUBSCRIPTION -> parameters.schema.schema.subscriptionType
                    else -> throw IllegalStateException("Unsupported operation type: ${definition.operation}")
                }

                is GJFragmentDefinition -> parameters.schema.schema.getType(definition.typeCondition.name) as? GraphQLCompositeType
                    ?: throw IllegalStateException("Type ${definition.typeCondition.name} not found in schema.")

                else -> throw IllegalArgumentException("Unsupported definition type: ${definition::class}")
            }
        }

        /**
         * Builds a [QueryPlan] from a selection set, parent type, and fragments.
         *
         * @param parameters The parameters containing the schema.
         * @param selectionSet The selection set.
         * @param parentType The parent GraphQL type.
         * @param fragmentsByName A map of fragment definitions by name.
         * @return A new [QueryPlan] instance.
         */
        private suspend fun build(
            parameters: Parameters,
            selectionSet: GJSelectionSet,
            documentKey: DocumentKey,
            parentType: GraphQLCompositeType,
            fragmentsByName: Map<String, GJFragmentDefinition>,
            useCache: Boolean = true,
            attribution: ExecutionAttribution?,
        ): QueryPlan {
            fun build(): QueryPlan =
                QueryPlanBuilder(parameters, fragmentsByName, emptyList())
                    .build(selectionSet, parentType, attribution, parameters.executionCondition, emptySet())

            return if (useCache) {
                val cacheKey = QueryPlanCacheKey(parameters.query, documentKey, parameters.schema.hashCode(), parameters.executeAccessChecksInModstrat)
                queryPlanCache.get(cacheKey) { _ -> build() }.await()
            } else {
                build()
            }
        }

        /**
         * Builds a [QueryPlan] from a [RawSelectionSet] for subquery execution.
         *
         * This method extracts the graphql-java AST directly from the RawSelectionSet
         * without re-parsing. The [RawSelectionSet.toSelectionSet] method inlines all
         * fragment spreads, so no fragment definitions are needed.
         *
         * @param parameters The parameters containing the schema and registry.
         * @param rss The RawSelectionSet containing the selections to execute.
         * @param attribution Attribution for this query plan execution.
         * @param executionCondition Condition under which this plan should execute.
         * @return A new [QueryPlan] instance.
         * @throws IllegalArgumentException if rss is empty
         */
        suspend fun buildFromSelections(
            parameters: Parameters,
            rss: RawSelectionSet,
            attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
            executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE
        ): QueryPlan {
            if (rss.isEmpty()) {
                throw IllegalArgumentException("RawSelectionSet.Empty is not supported for subquery execution")
            }

            val gjSelectionSet = rss.toSelectionSet()
            val parentType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(rss.type)

            val queryText = rss.printAsFieldSet()
            // DocumentKey is part of the cache key alongside queryText, so we just need
            // a stable identifier that distinguishes subqueries from regular operations.
            // The parentType name provides useful context for debugging.
            val documentKey = DocumentKey.Fragment("subquery:${parentType.name}")

            return build(
                parameters = parameters.copy(query = queryText, executionCondition = executionCondition),
                selectionSet = gjSelectionSet,
                documentKey = documentKey,
                parentType = parentType,
                fragmentsByName = emptyMap(),
                useCache = true,
                attribution = attribution
            )
        }
    }
}

/**
 * A stateful builder for QueryPlan. Instances of [QueryPlanBuilder] should only be used to build
 * a single QueryPlan.
 *
 * @param variablesResolvers: a list of [VariablesResolver]s.
 *   Any variable reference encountered by this builder must correspond to exactly one VariablesResolver
 */
private class QueryPlanBuilder(
    private val parameters: QueryPlan.Parameters,
    private val fragmentsByName: Map<String, GJFragmentDefinition>,
    private val variablesResolvers: List<VariablesResolver>
) {
    private val variableToResolver = variablesResolvers
        .flatMap { vr -> vr.variableNames.map { vname -> vname to vr } }
        .toMap()

    private val fragments: MutableMap<String, QueryPlan.FragmentDefinition> = mutableMapOf()

    private data class State(
        val selectionSet: QueryPlan.SelectionSet,
        val parentType: GraphQLCompositeType,
        val constraints: Constraints,
        val childPlans: List<QueryPlan>,
        val resolverCoordinate: Coordinate? = null
    )

    // Builders may cache results that are only valid for the specific input they were
    // created for, and are likely unsafe to reuse.
    // Guard against reuse
    private var built = false

    fun build(
        selectionSet: GJSelectionSet,
        parentType: GraphQLCompositeType,
        attribution: ExecutionAttribution?,
        executionCondition: QueryPlanExecutionCondition,
        seenRSSes: SeenRSSes,
    ): QueryPlan {
        check(!built) { "Builder cannot be reused" }
        built = true

        val state = buildState(
            selectionSet,
            State(
                selectionSet = QueryPlan.SelectionSet.empty,
                parentType = parentType,
                constraints = Constraints.Unconstrained,
                childPlans = emptyList()
            ),
            seenRSSes
        )

        val variableDefinitions = selectionSet.collectVariableDefinitions(
            parameters.schema.schema,
            parentType.asNamedElement().name,
            fragmentsByName
        )

        return QueryPlan(
            selectionSet = state.selectionSet,
            fragments = QueryPlan.Fragments(fragments.toMap()),
            variablesResolvers = variablesResolvers,
            parentType = parentType,
            childPlans = state.childPlans,
            astSelectionSet = selectionSet,
            attribution = attribution,
            executionCondition = executionCondition,
            variableDefinitions = variableDefinitions
        )
    }

    private fun buildState(
        selectionSet: GJSelectionSet,
        state: State,
        seenRSSes: SeenRSSes
    ): State =
        with(state) {
            selectionSet.selections
                .fold(state) { acc, sel ->
                    when (sel) {
                        is GJField -> processField(sel, acc, seenRSSes)
                        is GJInlineFragment -> processInlineFragment(sel, acc, seenRSSes)
                        is GJFragmentSpread -> processFragmentSpread(sel, acc, seenRSSes)
                        else -> throw IllegalStateException("Unexpected selection type: ${sel.javaClass}")
                    }
                }
        }

    private fun buildRequiredSelectionSetPlans(
        possibleParentTypes: MaskedSet<GraphQLObjectType>,
        field: GJField,
        seenRSSes: SeenRSSes
    ): List<QueryPlan> =
        possibleParentTypes.flatMap { parentType ->
            buildList {
                val resolverRsses = parameters.registry.getFieldResolverRequiredSelectionSets(parentType.name, field.name)
                addAll(buildChildPlansFromRequiredSelectionSets(resolverRsses, seenRSSes))

                val checkerRsses = parameters.registry.getFieldCheckerRequiredSelectionSets(parentType.name, field.name, parameters.executeAccessChecksInModstrat)
                addAll(buildChildPlansFromRequiredSelectionSets(checkerRsses, seenRSSes))
            }
        }

    private fun buildFieldTypeChildPlans(
        fieldType: GraphQLNamedOutputType,
        seenRSSes: SeenRSSes
    ): Map<GraphQLObjectType, Lazy<List<QueryPlan>>> {
        if (fieldType !is GraphQLCompositeType) {
            return emptyMap()
        }
        val possibleFieldTypes = parameters.schema.rels.possibleObjectTypes(fieldType)

        return possibleFieldTypes.mapNotNull { type ->
            val requiredSelectionSets =
                parameters.registry.getTypeCheckerRequiredSelectionSets(type.name, parameters.executeAccessChecksInModstrat)
            if (requiredSelectionSets.isEmpty()) return@mapNotNull null
            type to lazy {
                buildChildPlansFromRequiredSelectionSets(requiredSelectionSets, seenRSSes)
            }
        }.toMap()
    }

    private fun buildChildPlansFromRequiredSelectionSets(
        requiredSelectionSets: List<RequiredSelectionSet>,
        seenRSSes: SeenRSSes
    ): List<QueryPlan> {
        if (requiredSelectionSets.isEmpty()) {
            return emptyList()
        }
        return requiredSelectionSets.mapNotNull { rss ->
            // Skip if we've already processed this RSS
            if (rss in seenRSSes) {
                return@mapNotNull null
            }

            val newSeen = seenRSSes + rss
            // Use the typeName from ParsedSelections to determine correct target type
            val targetType = parameters.schema.schema.getType(rss.selections.typeName) as GraphQLCompositeType
            QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers)
                .build(
                    rss.selections.selections,
                    targetType,
                    attribution = rss.attribution,
                    executionCondition = rss.executionCondition,
                    seenRSSes = newSeen
                )
        }
    }

    /** Build a QueryPlan for each variable referenced by a field */
    private fun buildVariablesPlans(
        field: GJField,
        seenRSSes: SeenRSSes
    ): List<QueryPlan> {
        val varRefs = field.collectVariableReferences()
        if (varRefs.isEmpty()) return emptyList()

        return varRefs.mapNotNull { varRef ->
            val vResolver = variableToResolver[varRef] ?: return@mapNotNull null

            // if the variable resolver has a required selection set, build a QueryPlan for that selection set
            // Propagate seen RSS to prevent cycles through variable resolver RSSes
            vResolver.requiredSelectionSet?.let { rss ->
                // Skip if we've already processed this RSS
                if (rss in seenRSSes) {
                    return@mapNotNull null
                }
                val newSeen = seenRSSes + rss
                QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers)
                    .build(
                        rss.selections.selections,
                        parentType = parameters.schema.schema.getTypeAs(rss.selections.typeName),
                        attribution = rss.attribution,
                        executionCondition = rss.executionCondition,
                        seenRSSes = newSeen
                    )
            }
        }
    }

    private fun processField(
        sel: GJField,
        state: State,
        seenRSSes: SeenRSSes
    ): State =
        with(state) {
            val coord = (state.parentType.name to sel.name)
            val fieldDef = parameters.schema.schema.getFieldDefinition(coord.gj)
            val fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.type) as GraphQLNamedOutputType

            val possibleParentTypes = parameters.schema.rels.possibleObjectTypes(parentType)

            val fieldConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(possibleParentTypes)

            if (fieldConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            // Use the seenRSSes from the current context for cycle detection in RSS chains
            val fieldChildPlans = buildRequiredSelectionSetPlans(possibleParentTypes, sel, seenRSSes)
            val planChildPlans = buildVariablesPlans(sel, seenRSSes)
            val fieldTypeChildPlans = buildFieldTypeChildPlans(fieldType, seenRSSes)

            val resolverCoordinate = if (parameters.dispatcherRegistry.getFieldResolverDispatcher(parentType.name, sel.name) != null) {
                coord
            } else {
                state.resolverCoordinate
            }

            val subSelectionState = sel.selectionSet?.let { ss ->
                fieldType as GraphQLCompositeType
                val possibleFieldTypes = parameters.schema.rels.possibleObjectTypes(fieldType)
                val subSelectionConstraints = Constraints.Companion(
                    sel.directives,
                    possibleFieldTypes
                )
                buildState(
                    ss,
                    state.copy(
                        selectionSet = QueryPlan.SelectionSet.empty,
                        parentType = fieldType,
                        constraints = subSelectionConstraints,
                        resolverCoordinate = resolverCoordinate
                    ),
                    seenRSSes
                )
            }

            val field = Field(
                resultKey = sel.resultKey,
                constraints = fieldConstraints,
                field = sel,
                selectionSet = subSelectionState?.selectionSet,
                childPlans = fieldChildPlans,
                fieldTypeChildPlans = fieldTypeChildPlans,
                metadata = QueryPlan.FieldMetadata(
                    resolverCoordinate = resolverCoordinate
                )
            )

            state.copy(
                selectionSet = selectionSet + field,
                childPlans = childPlans + planChildPlans
            )
        }

    private fun processInlineFragment(
        sel: GJInlineFragment,
        state: State,
        seenRSSes: SeenRSSes
    ): State =
        with(state) {
            val typeConditionName = sel.typeCondition?.name ?: state.parentType.name
            val typeCondition = checkNotNull(parameters.schema.schema.getTypeAs<GraphQLCompositeType>(typeConditionName)) {
                "Type $typeConditionName not found"
            }
            val newConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(
                    parameters.schema.rels.possibleObjectTypes(typeCondition)
                )

            if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            val fragmentResult = processFragment(
                sel.selectionSet,
                typeConditionName,
                state.copy(
                    selectionSet = QueryPlan.SelectionSet.empty,
                    constraints = newConstraints
                ),
                seenRSSes
            )

            val inlineFragment = QueryPlan.InlineFragment(fragmentResult.selectionSet, newConstraints)
            copy(selectionSet = selectionSet + inlineFragment)
        }

    private fun processFragmentSpread(
        sel: GJFragmentSpread,
        state: State,
        seenRSSes: SeenRSSes
    ): State =
        with(state) {
            val name = sel.name
            val gjdef = checkNotNull(fragmentsByName[name]) { "Missing fragment definition: $name" }
            val fragType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(gjdef.typeCondition.name)

            val fragChildPlans = if (name !in fragments) {
                val fragState = buildState(
                    gjdef.selectionSet,
                    State(
                        selectionSet = QueryPlan.SelectionSet.empty,
                        parentType = fragType,
                        constraints = Constraints.Unconstrained,
                        childPlans = emptyList()
                    ),
                    seenRSSes
                )
                fragments[name] = QueryPlan.FragmentDefinition(fragState.selectionSet, gjdef)
                fragState.childPlans
            } else {
                emptyList()
            }

            val newConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(
                    parameters.schema.rels.possibleObjectTypes(fragType)
                )

            if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            copy(
                selectionSet = selectionSet + QueryPlan.FragmentSpread(name, newConstraints),
                childPlans = childPlans + fragChildPlans
            )
        }

    private fun processFragment(
        gjSelectionSet: GJSelectionSet,
        typeConditionName: String,
        state: State,
        seenRSSes: SeenRSSes
    ): State {
        val typeCondition = checkNotNull(parameters.schema.schema.getType(typeConditionName) as? GraphQLCompositeType) {
            "Type $typeConditionName not found in schema."
        }

        val newConstraints = state.constraints.narrowTypes(
            parameters.schema.rels.possibleObjectTypes(typeCondition)
        )

        // Check if this fragment combination is impossible
        if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
            return state
        }

        return buildState(
            gjSelectionSet,
            state.copy(
                constraints = newConstraints,
                parentType = typeCondition,
            ),
            seenRSSes
        )
    }
}

/** Tracks seen RequiredSelectionSets to prevent infinite loops during query plan building. */
private typealias SeenRSSes = Set<RequiredSelectionSet>

/** A pointer into a QueryPlan-able element of a GraphQL document */
sealed class DocumentKey {
    /** A pointer to a Fragment definition in a GraphQL document */
    data class Fragment(val name: String) : DocumentKey() {
        init {
            require(name.isNotEmpty()) { "Fragment name may not be an empty string" }
        }
    }

    /** A pointer to an Operation definition in a GraphQL document */
    data class Operation(val name: String?) : DocumentKey() {
        init {
            require(name == null || name.isNotEmpty()) { "Operation name may not be an empty string" }
        }
    }
}
