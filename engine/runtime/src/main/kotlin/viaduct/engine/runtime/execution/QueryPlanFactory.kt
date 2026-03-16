package viaduct.engine.runtime.execution

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.language.AbstractNode
import graphql.language.AstPrinter
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.gj
import viaduct.engine.api.select.ParsedSelectionsImpl
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.constraints.Constraints
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
            throw IllegalArgumentException("EngineSelectionSet.Empty is not supported for subquery execution")
        }
        return buildFromParsedSelections(
            parameters = parameters,
            parsedSelections = ParsedSelectionsImpl(
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
    class Cached(private val underlying: QueryPlanFactory = Default) : QueryPlanFactory {
        companion object {
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
            val executeAccessChecksInModstrat: Boolean,
        )

        private val cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .executor(queryPlanBuilderExecutor)
            .buildAsync<CacheKey, QueryPlan>()

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
                parameters.executeAccessChecksInModstrat,
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
                parameters.executeAccessChecksInModstrat,
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
            // Skip the copy when ALWAYS_EXECUTE is requested — the cached plan already has that
            // condition, so no allocation is needed and cached-instance identity is preserved.
            return if (executionCondition === ALWAYS_EXECUTE) cached else cached.copy(executionCondition = executionCondition)
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
    private val variablesResolvers: List<VariablesResolver>,
    private val rssBuildContext: RssBuildContext,
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
    ): State =
        with(state) {
            selectionSet.selections
                .fold(state) { acc, sel ->
                    when (sel) {
                        is GJField -> processField(sel, acc)
                        is GJInlineFragment -> processInlineFragment(sel, acc)
                        is GJFragmentSpread -> processFragmentSpread(sel, acc)
                        else -> throw IllegalStateException("Unexpected selection type: ${sel.javaClass}")
                    }
                }
        }

    private fun buildRequiredSelectionSetPlans(
        possibleParentTypes: MaskedSet<GraphQLObjectType>,
        field: GJField,
    ): List<QueryPlan> =
        possibleParentTypes.flatMap { parentType ->
            buildList {
                val resolverRsses = parameters.registry.getFieldResolverRequiredSelectionSets(parentType.name, field.name)
                addAll(buildChildPlansFromRequiredSelectionSets(resolverRsses))

                val checkerRsses = parameters.registry.getFieldCheckerRequiredSelectionSets(parentType.name, field.name, parameters.executeAccessChecksInModstrat)
                addAll(buildChildPlansFromRequiredSelectionSets(checkerRsses))
            }
        }

    private fun buildFieldTypeChildPlans(fieldType: GraphQLNamedOutputType): Map<GraphQLObjectType, Lazy<List<QueryPlan>>> {
        if (fieldType !is GraphQLCompositeType) {
            return emptyMap()
        }
        val possibleFieldTypes = parameters.schema.rels.possibleObjectTypes(fieldType)

        // Capture by local val so the lazy closure does not retain `this` (the QueryPlanBuilder).
        // For interface fields with many concrete types, most per-type lazies may never be forced
        // at runtime (only the resolved concrete type matters), so the builder would otherwise
        // be pinned for the lifetime of the cached plan.
        val capturedParameters = parameters
        val capturedContext = rssBuildContext

        val entries = possibleFieldTypes.mapNotNull { type ->
            val requiredSelectionSets =
                parameters.registry.getTypeCheckerRequiredSelectionSets(type.name, parameters.executeAccessChecksInModstrat)
            if (requiredSelectionSets.isEmpty()) return@mapNotNull null
            type to lazy {
                requiredSelectionSets.mapNotNull { rss -> buildRssPlan(capturedParameters, capturedContext, rss) }
            }
        }
        return if (entries.isEmpty()) emptyMap() else entries.toMap()
    }

    private fun buildChildPlansFromRequiredSelectionSets(requiredSelectionSets: List<RequiredSelectionSet>): List<QueryPlan> {
        if (requiredSelectionSets.isEmpty()) {
            return emptyList()
        }
        return requiredSelectionSets.mapNotNull { rss -> buildRssPlan(rss) }
    }

    /** Build a QueryPlan for each variable referenced by a node */
    private fun buildVariablesPlans(selection: AbstractNode<*>,): List<QueryPlan> {
        val varRefs = selection.collectVariableReferences()
        if (varRefs.isEmpty()) return emptyList()

        return varRefs.mapNotNull { varRef ->
            val vResolver = variableToResolver[varRef] ?: return@mapNotNull null
            // if the variable resolver has a required selection set, build a QueryPlan for that selection set
            vResolver.requiredSelectionSet?.let { rss -> buildRssPlan(rss) }
        }
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

            val fieldChildPlans = buildRequiredSelectionSetPlans(possibleParentTypes, sel)
            val planChildPlans = buildVariablesPlans(sel)
            val fieldTypeChildPlans = buildFieldTypeChildPlans(fieldType)

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
                )
            }

            val field = Field(
                resultKey = sel.resultKey,
                constraints = fieldConstraints,
                field = sel,
                selectionSet = subSelectionState?.selectionSet,
                childPlans = fieldChildPlans,
                fieldTypeChildPlans = fieldTypeChildPlans,
                metadata = if (resolverCoordinate != null) QueryPlan.FieldMetadata(resolverCoordinate) else QueryPlan.FieldMetadata.empty
            )

            state.copy(
                selectionSet = selectionSet + field,
                childPlans = (childPlans + planChildPlans).distinct()
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

            val fragmentResult = processFragment(
                sel.selectionSet,
                typeConditionName,
                state.copy(
                    selectionSet = QueryPlan.SelectionSet.empty,
                    constraints = newConstraints
                ),
            )

            val inlineFragment = QueryPlan.InlineFragment(fragmentResult.selectionSet, newConstraints)
            val variablesPlans = buildVariablesPlans(sel)
            copy(
                selectionSet = selectionSet + inlineFragment,
                childPlans = (childPlans + variablesPlans + fragmentResult.childPlans).distinct()
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

            if (name !in fragments) {
                val fragState = buildState(
                    gjdef.selectionSet,
                    State(
                        selectionSet = QueryPlan.SelectionSet.empty,
                        parentType = fragType,
                        constraints = Constraints.Unconstrained,
                        childPlans = emptyList()
                    ),
                )
                fragments[name] = QueryPlan.FragmentDefinition(fragState.selectionSet, gjdef, fragState.childPlans)
                fragState.childPlans
            }
            val fragChildPlans = fragments[name]!!.childPlans

            val newConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(
                    parameters.schema.rels.possibleObjectTypes(fragType)
                )

            // See processField: solve(Ctx.empty) catches directive-based drops only.
            if (newConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
            }

            val variablesPlans = buildVariablesPlans(sel)

            copy(
                selectionSet = selectionSet + QueryPlan.FragmentSpread(name, newConstraints),
                childPlans = (childPlans + variablesPlans + fragChildPlans).distinct()
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
 * file-level function (rather than a [QueryPlanBuilder] method) so that the
 * [QueryPlan.Field.fieldTypeChildPlans] lazy closures only need to capture [parameters] and
 * [rssBuildContext] — not the entire builder. For interface fields with many concrete types most
 * per-type lazies may never be forced at runtime, so keeping the builder reference alive would
 * pin [QueryPlanBuilder.fragmentsByName] and other per-build state indefinitely.
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
    val key = RssCacheKey(rss, parameters.schema.hashCode(), parameters.executeAccessChecksInModstrat)
    rssBuildContext.cache[key]?.let { return it }
    if (rss in rssBuildContext.building) return null // cycle within this local build tree

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
    localBuildContext.building.add(rss)
    val parentType = parameters.schema.schema.getTypeAs<GraphQLCompositeType>(rss.selections.typeName)
    val plan = QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers, localBuildContext)
        .build(rss.selections.selections, parentType, rss.attribution, rss.executionCondition)
    localBuildContext.building.remove(rss)
    rssBuildContext.cache.putIfAbsent(key, plan)
    // Populates the local cache so that fieldTypeChildPlans lazies (which capture localBuildContext)
    // find the plan immediately if the RSS is self-referential (i.e. selects a field whose return
    // type has the same type checker). No-op when localBuildContext === rssBuildContext.
    localBuildContext.cache.putIfAbsent(key, plan)
    return plan
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
    val rss: RequiredSelectionSet,
    val schemaHashCode: Int,
    val executeAccessChecksInModstrat: Boolean,
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
    val building: MutableSet<RequiredSelectionSet> = mutableSetOf(),
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
