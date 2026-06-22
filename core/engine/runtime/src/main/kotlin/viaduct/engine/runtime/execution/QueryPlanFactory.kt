package viaduct.engine.runtime.execution

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.benmanes.caffeine.cache.stats.CacheStats
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter
import com.github.benmanes.caffeine.cache.stats.StatsCounter
import graphql.language.Argument as GJArgument
import graphql.language.AstPrinter
import graphql.language.Directive as GJDirective
import graphql.language.Document
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.FragmentSpread as GJFragmentSpread
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.NodeUtil
import graphql.language.OperationDefinition
import graphql.language.SelectionSet as GJSelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.gj
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference.Kind
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.asNamedElement
import viaduct.graphql.utils.collectVariableDefinitions
import viaduct.graphql.utils.collectVariableReferences
import viaduct.utils.collections.MaskedSet

/**
 * Factory for building [QueryPlan] objects.
 *
 * The cache (if any) is scoped to this factory instance, ensuring that cached QueryPlans
 * are never reused across engine instances that have different
 * [viaduct.engine.api.RequiredSelectionSet] configurations.
 *
 * Use [QueryPlanFactory.Cached] for production (wraps [QueryPlanFactory.Default] with a
 * Caffeine async cache), or [QueryPlanFactory.Default] where no caching is needed.
 */
interface QueryPlanFactory {
    /**
     * Builds a [QueryPlan] from a GraphQL [Document].
     */
    suspend fun build(
        parameters: QueryPlan.Parameters,
        document: Document,
        documentKey: DocumentKey? = null,
        attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        rssBuildContext: RssBuildContext? = null,
    ): QueryPlan

    /**
     * Builds a [QueryPlan] from [ParsedSelections].
     */
    suspend fun buildFromParsedSelections(
        parameters: QueryPlan.Parameters,
        parsedSelections: ParsedSelections,
        attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE,
        rssBuildContext: RssBuildContext? = null,
    ): QueryPlan

    /**
     * Builds a [QueryPlan] from a [RequiredSelectionSet], preserving the RSS' variable resolvers.
     */
    suspend fun buildFromRequiredSelectionSet(
        parameters: QueryPlan.Parameters,
        rss: RequiredSelectionSet,
    ): QueryPlan

    /**
     * Convenience overload that extracts [ParsedSelections]-equivalent data from an
     * [EngineSelectionSet] and delegates to [buildFromParsedSelections].
     *
     * Note: the RSS's @skip/@include pre-processing is redundant since the
     * QueryPlanBuilder evaluates conditional directives internally.
     */
    suspend fun buildFromSelections(
        parameters: QueryPlan.Parameters,
        rss: EngineSelectionSet,
        attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
        executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE,
        fragmentsByName: Map<String, GJFragmentDefinition> = emptyMap()
    ): QueryPlan {
        if (rss.isEmpty()) {
            throw IllegalArgumentException("Empty EngineSelectionSet is not supported for subquery execution")
        }
        return buildFromParsedSelections(
            parameters = parameters,
            parsedSelections = ParsedSelections(
                typeName = rss.type,
                selections = rss.toSelectionSet(),
                fragmentMap = fragmentsByName,
            ),
            attribution = attribution,
            executionCondition = executionCondition,
        )
    }

    /** Builds a fresh [QueryPlan] on every call with no caching. */
    object Default : QueryPlanFactory {
        override suspend fun build(
            parameters: QueryPlan.Parameters,
            document: Document,
            documentKey: DocumentKey?,
            attribution: ExecutionAttribution?,
            rssBuildContext: RssBuildContext?,
        ): QueryPlan {
            val fragmentsByName = NodeUtil.getFragmentsByName(document)
            val docKey = resolveDocumentKey(document, documentKey)

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

            return QueryPlanBuilder(parameters, fragmentsByName, emptyList(), rssBuildContext ?: RssBuildContext(ConcurrentHashMap()))
                .build(selectionSet, parentType, attribution, parameters.executionCondition)
        }

        override suspend fun buildFromParsedSelections(
            parameters: QueryPlan.Parameters,
            parsedSelections: ParsedSelections,
            attribution: ExecutionAttribution?,
            executionCondition: QueryPlanExecutionCondition,
            rssBuildContext: RssBuildContext?,
        ): QueryPlan {
            val gjSelectionSet = parsedSelections.selections
            require(gjSelectionSet.selections.isNotEmpty()) {
                "Empty selections are not supported for completeSelectionSet execution"
            }
            val parentType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(parsedSelections.typeName)
            return QueryPlanBuilder(
                parameters.copy(query = parsedSelections.printAsFieldSet(), executionCondition = executionCondition),
                parsedSelections.fragmentMap,
                emptyList(),
                rssBuildContext ?: RssBuildContext(ConcurrentHashMap())
            ).build(gjSelectionSet, parentType, attribution, executionCondition)
        }

        override suspend fun buildFromRequiredSelectionSet(
            parameters: QueryPlan.Parameters,
            rss: RequiredSelectionSet,
        ): QueryPlan =
            checkNotNull(buildRssPlan(parameters, RssBuildContext(ConcurrentHashMap()), rss)) {
                "RequiredSelectionSet plan build was skipped due to a cycle"
            }

        /**
         * Determines the parent GraphQL type based on the given definition.
         *
         * @param parameters The parameters containing the schema.
         * @param definition The operation or fragment definition.
         * @return The parent [GraphQLCompositeType].
         */
        private fun getParentTypeFromDefinition(
            parameters: QueryPlan.Parameters,
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
    }

    /** Wraps a [QueryPlanFactory] with an instance-scoped async cache. */
    class Cached(
        private val underlying: QueryPlanFactory = Default,
        private val stats: QueryPlanFactoryStats = QueryPlanFactoryStats(),
        maximumSize: Long = DEFAULT_MAXIMUM_SIZE,
    ) : QueryPlanFactory {
        constructor(
            meterRegistry: MeterRegistry?,
            underlying: QueryPlanFactory = Default,
            maximumSize: Long = DEFAULT_MAXIMUM_SIZE,
        ) : this(underlying, QueryPlanFactoryStats(meterRegistry), maximumSize)

        companion object {
            const val DEFAULT_MAXIMUM_SIZE = 10_000L

            /**
             * Shared executor for Caffeine async cache population. This is static (companion-scoped)
             * rather than per-[Cached] instance because live threads are GC roots -- a per-instance
             * pool would pin the entire owning Viaduct object graph in memory until the pool is
             * explicitly shut down.
             */
            private val queryPlanBuilderExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
            ) { runnable ->
                Executors.defaultThreadFactory().newThread(runnable).also { it.setDaemon(true) }
            }

            /** Pre-computed dispatcher to avoid allocating a new wrapper on every cache miss. */
            private val queryPlanBuilderDispatcher = queryPlanBuilderExecutor.asCoroutineDispatcher()
        }

        private data class CacheKey(
            val documentText: String,
            val documentKey: DocumentKey,
            val schemaHashCode: Int,
        )

        private val cache = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .recordStats { stats }
            .executor(queryPlanBuilderExecutor)
            .buildAsync<CacheKey, QueryPlan>()
        private val synchronousCache = cache.synchronous()

        init {
            stats.meterRegistry?.let {
                Gauge.builder(QueryPlanFactoryStats.CACHE_SIZE_METRIC_NAME, synchronousCache) { cache ->
                    cache.estimatedSize().toDouble()
                }.register(it)
            }
        }

        @VisibleForTest
        internal val cacheStats: CacheStats
            get() {
                synchronousCache.cleanUp()
                return synchronousCache.stats()
            }

        /**
         * Global cache for RSS child plans, shared across all top-level plan build passes.
         *
         * 2 reasons this is a plain [ConcurrentHashMap]:
         * - Bounded key space: there's a fixed number of RSSes, unlike operations.
         * - Deadlock avoidance: using async builds to avoid duplicate work would risk deadlock
         *   if two concurrent build passes have cyclical RSS dependencies.
         *   This may do duplicate work on concurrent cold misses, but always terminates.
         */
        private val rssPlanCache = ConcurrentHashMap<RssCacheKey, QueryPlan>()

        override suspend fun build(
            parameters: QueryPlan.Parameters,
            document: Document,
            documentKey: DocumentKey?,
            attribution: ExecutionAttribution?,
            rssBuildContext: RssBuildContext?,
        ): QueryPlan {
            val resolvedKey = resolveDocumentKey(document, documentKey)
            val cacheKey = CacheKey(
                parameters.query,
                resolvedKey,
                parameters.schema.hashCode(),
            )
            return cache.get(cacheKey) { _, _ ->
                CoroutineScope(queryPlanBuilderDispatcher).future {
                    underlying.build(parameters, document, documentKey, attribution, RssBuildContext(rssPlanCache))
                }
            }.await()
        }

        override suspend fun buildFromParsedSelections(
            parameters: QueryPlan.Parameters,
            parsedSelections: ParsedSelections,
            attribution: ExecutionAttribution?,
            executionCondition: QueryPlanExecutionCondition,
            rssBuildContext: RssBuildContext?,
        ): QueryPlan {
            val queryText = parsedSelections.printAsFieldSet()
            val cacheKey = CacheKey(
                queryText,
                DocumentKey.Fragment("subquery:${parsedSelections.typeName}"),
                parameters.schema.hashCode(),
            )
            // Cache using ALWAYS_EXECUTE so that executionCondition (which may be a SAM lambda
            // with identity-based hashCode) doesn't prevent cache sharing across calls with
            // different conditions but identical query text and schema. executionCondition is
            // pure metadata stored on the root plan but not used during plan construction.
            val cached = cache.get(cacheKey) { _, _ ->
                CoroutineScope(queryPlanBuilderDispatcher).future {
                    underlying.buildFromParsedSelections(parameters, parsedSelections, attribution, ALWAYS_EXECUTE, RssBuildContext(rssPlanCache))
                }
            }.await()
            // Patch executionCondition and attribution onto the cached plan. Both are pure metadata
            // and not part of the cache key (executionCondition may be a SAM lambda with
            // identity-based hashCode; attribution varies per calling resolver). Skip the copy
            // when both already match to preserve cached-instance identity.
            return if (executionCondition === ALWAYS_EXECUTE && cached.attribution == attribution) {
                cached
            } else {
                cached.copy(executionCondition = executionCondition, attribution = attribution)
            }
        }

        override suspend fun buildFromRequiredSelectionSet(
            parameters: QueryPlan.Parameters,
            rss: RequiredSelectionSet,
        ): QueryPlan =
            // This bypasses the document-plan cache but still uses the RSS plan cache.
            checkNotNull(buildRssPlan(parameters, RssBuildContext(rssPlanCache), rss)) {
                "RequiredSelectionSet plan build was skipped due to a cycle"
            }
    }
}

class QueryPlanFactoryStats(
    internal val meterRegistry: MeterRegistry? = null,
) : StatsCounter {
    companion object {
        const val RESULT_TAG_NAME = "result"
        private const val CACHE_METRIC_NAME_PREFIX = "viaduct.query_plan.cache"
        const val CACHE_REQUESTS_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.requests"
        const val CACHE_LOADS_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.loads"
        const val CACHE_LOAD_DURATION_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.load_duration"
        const val CACHE_EVICTIONS_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.evictions"
        const val CACHE_EVICTION_WEIGHT_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.eviction_weight"
        const val CACHE_SIZE_METRIC_NAME = "$CACHE_METRIC_NAME_PREFIX.size"
    }

    private val delegate = ConcurrentStatsCounter()
    private val hitCounter = meterRegistry?.counter(
        CACHE_REQUESTS_METRIC_NAME,
        RESULT_TAG_NAME,
        "hit",
    )
    private val missCounter = meterRegistry?.counter(
        CACHE_REQUESTS_METRIC_NAME,
        RESULT_TAG_NAME,
        "miss",
    )
    private val loadSuccessCounter = meterRegistry?.counter(
        CACHE_LOADS_METRIC_NAME,
        RESULT_TAG_NAME,
        "success",
    )
    private val loadFailureCounter = meterRegistry?.counter(
        CACHE_LOADS_METRIC_NAME,
        RESULT_TAG_NAME,
        "failure",
    )
    private val loadSuccessTimer = meterRegistry?.let {
        Timer.builder(CACHE_LOAD_DURATION_METRIC_NAME)
            .tag(RESULT_TAG_NAME, "success")
            .register(it)
    }
    private val loadFailureTimer = meterRegistry?.let {
        Timer.builder(CACHE_LOAD_DURATION_METRIC_NAME)
            .tag(RESULT_TAG_NAME, "failure")
            .register(it)
    }
    private val evictionCounter = meterRegistry?.counter(
        CACHE_EVICTIONS_METRIC_NAME,
    )
    private val evictionWeightCounter = meterRegistry?.counter(
        CACHE_EVICTION_WEIGHT_METRIC_NAME,
    )

    override fun recordHits(count: Int) {
        delegate.recordHits(count)
        hitCounter?.increment(count.toDouble())
    }

    override fun recordMisses(count: Int) {
        delegate.recordMisses(count)
        missCounter?.increment(count.toDouble())
    }

    override fun recordLoadSuccess(loadTime: Long) {
        delegate.recordLoadSuccess(loadTime)
        loadSuccessCounter?.increment()
        loadSuccessTimer?.record(loadTime, TimeUnit.NANOSECONDS)
    }

    override fun recordLoadFailure(loadTime: Long) {
        delegate.recordLoadFailure(loadTime)
        loadFailureCounter?.increment()
        loadFailureTimer?.record(loadTime, TimeUnit.NANOSECONDS)
    }

    override fun recordEviction() {
        delegate.recordEviction(1, RemovalCause.SIZE)
        evictionCounter?.increment()
    }

    override fun recordEviction(weight: Int) {
        delegate.recordEviction(weight, RemovalCause.SIZE)
        evictionCounter?.increment()
        evictionWeightCounter?.increment(weight.toDouble())
    }

    override fun recordEviction(
        weight: Int,
        cause: RemovalCause,
    ) {
        delegate.recordEviction(weight, cause)
        evictionCounter?.increment()
        evictionWeightCounter?.increment(weight.toDouble())
    }

    override fun snapshot(): CacheStats = delegate.snapshot()
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
    private val variablesResolvers: List<VariablesResolver>,
    private val rssBuildContext: RssBuildContext,
) {
    private val variableToResolver = variablesResolvers
        .flatMap { vr -> vr.variableNames.map { vname -> vname to vr } }
        .toMap()

    private val fragments: MutableMap<String, QueryPlan.FragmentDefinition> = mutableMapOf()
    private val fieldTypeChildPlans = LazyFieldTypeChildPlans(parameters, rssBuildContext)

    private data class State(
        val selectionSet: QueryPlan.SelectionSet,
        val parentType: GraphQLCompositeType,
        val constraints: Constraints,
        val childPlanIds: List<RequiredSelectionSet.Id>,
        val indexBuilder: Index.Builder<RequiredSelectionSet.Id, QueryPlan>,
        // @skip/@include variable references from the selection that owns this selection set.
        // Field collection later sees only the child SelectionSet, so these boundary facts need to
        // travel with it.
        val selectionSetEnclosingVariableReferences: List<SelectionVariableReference> = emptyList(),
        val resolverCoordinate: Coordinate? = null,
        val resolvedByCoordinate: Coordinate? = null,
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
        requiredSelectionSetId: RequiredSelectionSet.Id? = null,
    ): QueryPlan {
        check(!built) { "Builder cannot be reused" }
        built = true

        val state = buildState(
            selectionSet,
            State(
                selectionSet = QueryPlan.SelectionSet.empty,
                parentType = parentType,
                constraints = Constraints.Unconstrained
                    .narrowTypes(parameters.schema.rels.possibleObjectTypes(parentType)),
                childPlanIds = emptyList(),
                indexBuilder = QueryPlanIndex.builder()
            ),
        )

        val plannedFragments = QueryPlan.Fragments(fragments.toMap())
        val activeVariableNames = state.selectionSet.activeVariableNames(plannedFragments)
        val variableDefinitions = selectionSet.collectVariableDefinitions(
            parameters.schema.schema,
            parentType.asNamedElement().name,
            fragmentsByName
        ).filter { it.name in activeVariableNames }
        val activeVariablesResolvers = variablesResolvers
            .filter { resolver -> resolver.variableNames.any { it in activeVariableNames } }

        return QueryPlan(
            selectionSet = state.selectionSet,
            fragments = plannedFragments,
            variablesResolvers = activeVariablesResolvers,
            parentType = parentType,
            childPlanIds = state.childPlanIds,
            baseIndex = state.indexBuilder.build(),
            astSelectionSet = selectionSet,
            attribution = attribution,
            executionCondition = executionCondition,
            variableDefinitions = variableDefinitions,
            requiredSelectionSetId = requiredSelectionSetId,
        )
    }

    private fun buildState(
        selectionSet: GJSelectionSet,
        state: State,
    ): State {
        val result = selectionSet.selections
            .fold(state) { acc, sel ->
                when (sel) {
                    is GJField -> processField(sel, acc)
                    is GJInlineFragment -> processInlineFragment(sel, acc)
                    is GJFragmentSpread -> processFragmentSpread(sel, acc)
                    else -> throw IllegalStateException("Unexpected selection type: ${sel.javaClass}")
                }
            }
        return result.copy(
            selectionSet = result.selectionSet.withEnclosingVariableReferences(state.selectionSetEnclosingVariableReferences)
        )
    }

    private fun QueryPlan.SelectionSet.activeVariableNames(fragments: QueryPlan.Fragments): Set<String> =
        buildSet {
            val visitedFragments = mutableSetOf<String>()

            fun addReferences(references: List<SelectionVariableReference>) {
                references.forEach { add(it.name) }
            }

            fun QueryPlan.Selection.isStaticallyDropped(): Boolean = constraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop

            lateinit var visitSelection: (QueryPlan.Selection) -> Unit

            fun visitSelectionSet(selectionSet: QueryPlan.SelectionSet) {
                addReferences(selectionSet.enclosingVariableReferences)
                selectionSet.selections.forEach { visitSelection(it) }
            }

            visitSelection = { selection ->
                if (!selection.isStaticallyDropped()) {
                    addReferences(selection.variableReferences)
                    when (selection) {
                        is QueryPlan.CollectedField -> selection.selectionSet?.let(::visitSelectionSet)
                        is QueryPlan.Field -> selection.selectionSet?.let(::visitSelectionSet)
                        is QueryPlan.InlineFragment -> visitSelectionSet(selection.selectionSet)
                        is QueryPlan.FragmentSpread -> {
                            if (visitedFragments.add(selection.name)) {
                                val fragmentDefinition = fragments[selection.name]
                                if (fragmentDefinition != null) {
                                    addReferences(fragmentDefinition.variableReferences)
                                    visitSelectionSet(fragmentDefinition.selectionSet)
                                }
                            }
                        }
                    }
                }
            }

            visitSelectionSet(this@activeVariableNames)
        }

    private data class ChildPlanBuildResult(
        val id: RequiredSelectionSet.Id,
        val parentType: GraphQLCompositeType,
        val plan: QueryPlan?,
        val originCoordinate: Coordinate? = null,
    ) {
        fun toFieldChildPlan(): FieldChildPlan =
            FieldChildPlan(
                id,
                parentType,
                requireNotNull(originCoordinate) {
                    "Field child plan results must have an origin coordinate"
                },
            )
    }

    private fun buildRequiredSelectionSetPlans(
        possibleParentTypes: MaskedSet<GraphQLObjectType>,
        field: GJField,
    ): List<ChildPlanBuildResult> =
        possibleParentTypes.flatMap { parentType ->
            val originCoordinate: Coordinate = parentType.name to field.name
            buildList {
                val resolverRsses = parameters.registry.getFieldResolverRequiredSelectionSets(parentType.name, field.name)
                addAll(buildChildPlansFromRequiredSelectionSets(resolverRsses, originCoordinate))

                val checkerRsses = parameters.registry.getFieldCheckerRequiredSelectionSets(parentType.name, field.name)
                addAll(buildChildPlansFromRequiredSelectionSets(checkerRsses, originCoordinate))
            }
        }

    private fun buildFieldTypeChildPlans(fieldType: GraphQLNamedOutputType): FieldTypeChildPlans {
        if (fieldType !is GraphQLCompositeType) {
            return FieldTypeChildPlans.empty
        }
        return fieldTypeChildPlans
    }

    private fun buildChildPlansFromRequiredSelectionSets(
        requiredSelectionSets: List<RequiredSelectionSet>,
        originCoordinate: Coordinate? = null,
    ): List<ChildPlanBuildResult> =
        requiredSelectionSets.map { rss ->
            buildChildPlan(rss, originCoordinate)
        }

    /**
     * Build QueryPlans for variable references owned by the current selection.
     *
     * The builder already recurses into field subselections and fragment bodies, so this must not
     * walk a whole selection subtree. Nested variable child plan ids flow back through [State.childPlanIds].
     */
    private fun buildVariablesPlans(variableReferences: Set<String>): List<ChildPlanBuildResult> =
        variableReferences.mapNotNull { varRef ->
            val vResolver = variableToResolver[varRef] ?: return@mapNotNull null
            // if the variable resolver has a required selection set, build a QueryPlan for that selection set
            vResolver.requiredSelectionSet?.let { rss ->
                buildChildPlan(rss)
            }
        }

    private fun buildChildPlan(
        rss: RequiredSelectionSet,
        originCoordinate: Coordinate? = null,
    ): ChildPlanBuildResult =
        ChildPlanBuildResult(
            id = rss.id,
            parentType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(rss.selections.typeName),
            plan = buildRssPlan(rss),
            originCoordinate = originCoordinate,
        )

    private data class SelectionVariableReferences(
        val references: List<SelectionVariableReference>,
        val variablePlanNames: Set<String>,
        val collectionVariableReferences: List<SelectionVariableReference>
    )

    private fun GJField.variableReferences(): SelectionVariableReferences = variableReferencesIn(arguments, directives)

    private fun GJInlineFragment.variableReferences(): SelectionVariableReferences = variableReferencesIn(directives = directives)

    private fun GJFragmentSpread.variableReferences(): SelectionVariableReferences = variableReferencesIn(directives = directives)

    private fun GJFragmentDefinition.variableReferences(): SelectionVariableReferences = variableReferencesIn(directives = directives)

    private fun variableReferencesIn(
        arguments: List<GJArgument> = emptyList(),
        directives: List<GJDirective> = emptyList()
    ): SelectionVariableReferences {
        val references = linkedSetOf<SelectionVariableReference>()

        arguments.forEach { argument ->
            argument.collectVariableReferences().forEach { variableName ->
                references += SelectionVariableReference(variableName, Kind.FIELD_ARGUMENT)
            }
        }
        directives.forEach { directive ->
            val conditionalVariableName = Constraints.conditionalDirectiveVariableName(directive)
            when {
                conditionalVariableName != null -> references += SelectionVariableReference(conditionalVariableName, Kind.CONDITIONAL_DIRECTIVE)
                Constraints.isConditionalDirective(directive) -> Unit
                else -> directive.collectVariableReferences().forEach { variableName ->
                    references += SelectionVariableReference(variableName, Kind.DIRECTIVE)
                }
            }
        }

        val variablePlanNames = references.mapTo(linkedSetOf()) { it.name }
        val collectionVariableReferences = references.filter { it.kind == Kind.CONDITIONAL_DIRECTIVE }
        return SelectionVariableReferences(references.toList(), variablePlanNames, collectionVariableReferences)
    }

    private fun buildRssPlan(rss: RequiredSelectionSet): QueryPlan? = buildRssPlan(parameters, rssBuildContext, rss)

    private fun processField(
        sel: GJField,
        state: State,
    ): State =
        with(state) {
            val coord = (state.parentType.name to sel.name)
            val fieldDef = parameters.schema.schema.getFieldDefinition(coord.gj)
            val fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.type) as GraphQLNamedOutputType

            val possibleParentTypes = parameters.schema.rels.possibleObjectTypes(parentType)

            val fieldConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(possibleParentTypes)

            // solve(Ctx.empty) only catches directive-based drops (e.g. @skip(if: true) with a
            // literal boolean). Type-based pruning is handled by narrowTypes() returning
            // Constraints.Drop when the type intersection is empty — Ctx.empty has null parentTypes
            // which short-circuits type resolution to Collect (same as the previous
            // MaskedSet.empty() behaviour). Constraints.Drop.solve() always returns Drop regardless.
            if (fieldConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            val variableReferences = sel.variableReferences()
            val fieldChildPlanResults = buildRequiredSelectionSetPlans(possibleParentTypes, sel)
            val fieldChildPlans = fieldChildPlanResults.map { it.toFieldChildPlan() }
            val planChildPlanResults = buildVariablesPlans(variableReferences.variablePlanNames)
            val planChildPlanIds = planChildPlanResults.map { it.id }
            val fieldTypeChildPlans = buildFieldTypeChildPlans(fieldType)
            indexBuilder.addAll(planChildPlanResults.mapNotNull { it.plan })
            for (fieldChildPlanResult in fieldChildPlanResults) {
                fieldChildPlanResult.plan?.let(indexBuilder::add)
            }

            val resolvedByCoordinate = if (parameters.dispatcherRegistry.getFieldResolverDispatcher(parentType.name, sel.name) != null) {
                coord
            } else {
                state.resolvedByCoordinate
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
                    State(
                        selectionSet = QueryPlan.SelectionSet.empty,
                        parentType = fieldType,
                        constraints = subSelectionConstraints,
                        childPlanIds = emptyList(),
                        indexBuilder = indexBuilder,
                        selectionSetEnclosingVariableReferences = variableReferences.collectionVariableReferences,
                        resolverCoordinate = resolverCoordinate,
                        resolvedByCoordinate = resolvedByCoordinate,
                    ),
                )
            }

            val field = Field(
                resultKey = sel.resultKey,
                constraints = fieldConstraints,
                field = sel,
                selectionSet = subSelectionState?.selectionSet,
                childPlans = fieldChildPlans,
                fieldTypeChildPlans = fieldTypeChildPlans,
                metadata = if (resolvedByCoordinate != null) {
                    QueryPlan.FieldMetadata(resolvedByCoordinate = resolvedByCoordinate)
                } else {
                    QueryPlan.FieldMetadata.empty
                },
                variableReferences = variableReferences.references
            )

            state.copy(
                selectionSet = selectionSet + field,
                childPlanIds = (childPlanIds + planChildPlanIds + (subSelectionState?.childPlanIds ?: emptyList())).distinct(),
            )
        }

    private fun processInlineFragment(
        sel: GJInlineFragment,
        state: State,
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

            // See processField: solve(Ctx.empty) catches directive-based drops only.
            if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            val variableReferences = sel.variableReferences()
            val variablesPlanDependencies = buildVariablesPlans(variableReferences.variablePlanNames)
            val variablesPlanIds = variablesPlanDependencies.map { it.id }
            indexBuilder.addAll(variablesPlanDependencies.mapNotNull { it.plan })
            val fragmentSelectionSetEnclosingReferences = selectionSetEnclosingVariableReferences + variableReferences.collectionVariableReferences
            val fragmentResult = processFragment(
                sel.selectionSet,
                typeConditionName,
                State(
                    selectionSet = QueryPlan.SelectionSet.empty,
                    parentType = state.parentType,
                    constraints = newConstraints,
                    childPlanIds = emptyList(),
                    indexBuilder = indexBuilder,
                    selectionSetEnclosingVariableReferences = fragmentSelectionSetEnclosingReferences,
                    resolverCoordinate = resolverCoordinate,
                    resolvedByCoordinate = resolvedByCoordinate
                ),
            )

            val inlineFragment = QueryPlan.InlineFragment(
                fragmentResult.selectionSet,
                newConstraints,
                variableReferences.references,
            )
            copy(
                selectionSet = selectionSet + inlineFragment,
                childPlanIds = (childPlanIds + variablesPlanIds + fragmentResult.childPlanIds).distinct(),
            )
        }

    private fun processFragmentSpread(
        sel: GJFragmentSpread,
        state: State,
    ): State =
        with(state) {
            val name = sel.name
            val gjdef = checkNotNull(fragmentsByName[name]) { "Missing fragment definition: $name" }
            val fragType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(gjdef.typeCondition.name)

            val newConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(
                    parameters.schema.rels.possibleObjectTypes(fragType)
                )

            // See processField: solve(Ctx.empty) catches directive-based drops only.
            if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            if (name !in fragments) {
                val fragmentVariableReferences = gjdef.variableReferences()
                val fragState = buildState(
                    gjdef.selectionSet,
                    State(
                        selectionSet = QueryPlan.SelectionSet.empty,
                        parentType = fragType,
                        constraints = Constraints.Unconstrained
                            .narrowTypes(parameters.schema.rels.possibleObjectTypes(fragType)),
                        childPlanIds = emptyList(),
                        indexBuilder = Index.Builder()
                    ),
                )
                val fragmentVariablesPlanDependencies = buildVariablesPlans(fragmentVariableReferences.variablePlanNames)
                val fragmentVariablesPlanIds = fragmentVariablesPlanDependencies.map { it.id }
                val fragmentChildPlanIds =
                    (fragmentVariablesPlanIds + fragState.childPlanIds).distinct()
                fragments[name] = QueryPlan.FragmentDefinition(
                    fragState.selectionSet,
                    gjdef,
                    fragmentChildPlanIds,
                    fragmentVariableReferences.references,
                    Index.Builder<RequiredSelectionSet.Id, QueryPlan>()
                        .addAll(fragmentVariablesPlanDependencies.mapNotNull { it.plan })
                        .add(fragState.indexBuilder.build())
                        .build()
                )
            }
            val fragmentDefinition = fragments.getValue(name)

            val variableReferences = sel.variableReferences()
            val variablesPlanDependencies = buildVariablesPlans(variableReferences.variablePlanNames)
            val variablesPlanIds = variablesPlanDependencies.map { it.id }
            indexBuilder
                .addAll(variablesPlanDependencies.mapNotNull { it.plan })
                .add(fragmentDefinition.index)

            copy(
                selectionSet = selectionSet + QueryPlan.FragmentSpread(
                    name,
                    newConstraints,
                    variableReferences.references,
                ),
                childPlanIds = (childPlanIds + variablesPlanIds + fragmentDefinition.childPlanIds).distinct(),
            )
        }

    private fun processFragment(
        gjSelectionSet: GJSelectionSet,
        typeConditionName: String,
        state: State,
    ): State {
        val typeCondition = checkNotNull(parameters.schema.schema.getType(typeConditionName) as? GraphQLCompositeType) {
            "Type $typeConditionName not found in schema."
        }

        val newConstraints = state.constraints.narrowTypes(
            parameters.schema.rels.possibleObjectTypes(typeCondition)
        )

        // Check if this fragment combination is impossible.
        // See processField: solve(Ctx.empty) catches directive-based drops only.
        if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
            return state
        }

        return buildState(
            gjSelectionSet,
            state.copy(
                constraints = newConstraints,
                parentType = typeCondition,
            ),
        )
    }
}

/**
 * Builds (or returns a cached) [QueryPlan] for a single [RequiredSelectionSet]. Extracted as a
 * file-level function (rather than a [QueryPlanBuilder] method) so that
 * [QueryPlan.Field.fieldTypeChildPlans] can build the concrete type's plans on demand without
 * retaining the entire builder. For interface fields with many concrete types, precomputing a
 * lazy per possible type retains many closures that may never be used.
 *
 * Uses a two-level context model so that globally cached plans are never truncated by cycle
 * detection from another concurrent RSS build.
 *
 * Returns null if [rss] is already being built in the current local tree (cycle detected).
 */
private fun buildRssPlan(
    parameters: QueryPlan.Parameters,
    rssBuildContext: RssBuildContext,
    rss: RequiredSelectionSet,
): QueryPlan? {
    val key = RssCacheKey(
        requiredSelectionSetId = rss.id,
        schemaHashCode = parameters.schema.hashCode(),
    )
    rssBuildContext.cache[key]?.let { return it }
    if (rss.id in rssBuildContext.building) {
        return null
    }

    val localBuildContext = if (rssBuildContext.building.isEmpty()) {
        // Top-level: use a fresh local cache so sub-RSS plans built transitively are not stored
        // in the global rssPlanCache. Without isolation, a sub-RSS encountered mid-build (while
        // ancestors are in `building`) would be globally cached in truncated form.
        RssBuildContext(ConcurrentHashMap())
    } else {
        // Inside a local build: share the current context so cycle detection covers the full
        // transitive tree, not just one level.
        rssBuildContext
    }
    localBuildContext.building.add(rss.id)
    val plan = try {
        val parentType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(rss.selections.typeName)
        QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers, localBuildContext)
            .build(
                rss.selections.selections,
                parentType,
                rss.attribution,
                rss.executionCondition,
                rss.id,
            )
    } finally {
        localBuildContext.building.remove(rss.id)
    }
    rssBuildContext.cache.putIfAbsent(key, plan)
    // Populates the local cache so that fieldTypeChildPlans lookups find the plan immediately
    // if the RSS is self-referential (i.e. selects a field whose return
    // type has the same type checker). No-op when localBuildContext === rssBuildContext.
    localBuildContext.cache.putIfAbsent(key, plan)
    return plan
}

private class LazyFieldTypeChildPlans(
    private val parameters: QueryPlan.Parameters,
    private val rssBuildContext: RssBuildContext,
) : FieldTypeChildPlans {
    override fun plansFor(objectType: GraphQLObjectType): List<QueryPlan> {
        val requiredSelectionSets = parameters.registry.getTypeCheckerRequiredSelectionSets(objectType.name)
        if (requiredSelectionSets.isEmpty()) {
            return emptyList()
        }
        return requiredSelectionSets.mapNotNull { rss ->
            buildRssPlan(parameters, rssBuildContext, rss)
        }
    }
}

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

/** Cache key for globally-shared RSS plan entries in [QueryPlanFactory.Cached]. */
data class RssCacheKey(
    val requiredSelectionSetId: RequiredSelectionSet.Id,
    val schemaHashCode: Int,
)

/**
 * Context for building RSS child plans.
 *
 * @property cache On the outer context this is the global RSS plan cache on
 *   [QueryPlanFactory.Cached], shared across all build passes. On fresh local contexts
 *   (created per cache miss in [QueryPlanBuilder]) this is a local cache that deduplicates
 *   sub-RSS plans within a single RSS's transitive build tree.
 * @property building Cycle-detection set for a single RSS's transitive build tree.
 *   Initialized with the root RSS of the local build; entries are added before visiting a
 *   sub-RSS and removed after (via finally), so it reflects the current build call stack.
 */
class RssBuildContext(
    val cache: ConcurrentHashMap<RssCacheKey, QueryPlan>,
    val building: MutableSet<RequiredSelectionSet.Id> = mutableSetOf(),
)

private fun ParsedSelections.printAsFieldSet(): String = selections.selections.joinToString("\n") { AstPrinter.printAstCompact(it) }

/**
 * Resolves the [DocumentKey] for a [Document], using [documentKey] if provided or
 * auto-detecting the first operation or fragment.
 */
private fun resolveDocumentKey(
    document: Document,
    documentKey: DocumentKey?
): DocumentKey {
    val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
    return documentKey
        ?: operations.firstOrNull()?.let { DocumentKey.Operation(it.name) }
        ?: document.getFirstDefinitionOfType(GJFragmentDefinition::class.java).getOrNull()?.let { DocumentKey.Fragment(it.name) }
        ?: throw IllegalStateException("document contains no fragment or operation definitions")
}
