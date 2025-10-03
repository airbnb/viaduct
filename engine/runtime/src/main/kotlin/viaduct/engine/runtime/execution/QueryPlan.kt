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
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import java.util.concurrent.Executors
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.future.await
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.graphql.utils.collectVariableReferences

/**
 * QueryPlan is an intermediate representation of a GraphQL selection set.
 * It includes models of viaduct-specific concepts, including required selection sets
 * and their variables.
 *
 * @param childPlans child QueryPlan objects. These will be resolved before any
 * selections in this QueryPlan are resolved.
 */
data class QueryPlan(
    val selectionSet: SelectionSet,
    val fragments: Fragments,
    val variablesResolvers: List<VariablesResolver>,
    val parentType: GraphQLOutputType,
    val childPlans: List<QueryPlan>,
    val attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
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
        val executeAccessChecksInModstrat: Boolean
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
        val fieldTypeChildPlans: Map<GraphQLObjectType, List<QueryPlan>>,
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
     */
    data class Field(
        val resultKey: String,
        override val constraints: Constraints,
        val field: GJField,
        val selectionSet: SelectionSet?,
        val childPlans: List<QueryPlan>,
        val fieldTypeChildPlans: Map<GraphQLObjectType, List<QueryPlan>>,
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

    data class FragmentDefinition(val selectionSet: SelectionSet)

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

        // TODO: fix cache
        private data class QueryPlanCacheKey(val documentText: String, val documentKey: DocumentKey, val schemaHashCode: Int, val executeAccessChecksInModstrat: Boolean, val inCheckerContext: Boolean)

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
         * @param inCheckerContext Whether the document we're building a query plan for is a checker RSS or
         *                         a checker variable resolver RSS
         * @return A new [QueryPlan] instance.
         */
        suspend fun build(
            parameters: Parameters,
            document: Document,
            documentKey: DocumentKey? = null,
            useCache: Boolean = true,
            attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
            inCheckerContext: Boolean = false
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

            return build(parameters, selectionSet, docKey, parentType, fragmentsByName, useCache, attribution, inCheckerContext)
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
            inCheckerContext: Boolean,
        ): QueryPlan {
            fun build(): QueryPlan =
                QueryPlanBuilder(parameters, fragmentsByName, emptyList())
                    .build(selectionSet, parentType, attribution, inCheckerContext)

            return if (useCache) {
                val cacheKey = QueryPlanCacheKey(parameters.query, documentKey, parameters.schema.hashCode(), parameters.executeAccessChecksInModstrat, inCheckerContext)
                queryPlanCache.get(cacheKey) { _ -> build() }.await()
            } else {
                build()
            }
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

    /**
     * @param inCheckerContext Whether the current plan being built is for a checker RSS or a checker variable RSS
     */
    private data class State(
        val selectionSet: QueryPlan.SelectionSet,
        val parentType: GraphQLCompositeType,
        val constraints: Constraints,
        val childPlans: List<QueryPlan>,
        val inCheckerContext: Boolean
    )

    // Builders may cache results that are only valid for the specific input they were
    // created for, and are likely unsafe to reuse.
    // Guard against reuse
    private var built = false

    fun build(
        selectionSet: GJSelectionSet,
        parentType: GraphQLCompositeType,
        attribution: ExecutionAttribution?,
        inCheckerContext: Boolean
    ): QueryPlan {
        return createQueryPlan(selectionSet, parentType, attribution, inCheckerContext)
    }

    private fun createQueryPlan(
        selectionSet: GJSelectionSet,
        parentType: GraphQLCompositeType,
        attribution: ExecutionAttribution?,
        inCheckerContext: Boolean
    ): QueryPlan {
        check(!built) { "Builder cannot be reused" }
        built = true

        val state = buildState(
            selectionSet,
            State(
                selectionSet = QueryPlan.SelectionSet.empty,
                parentType = parentType,
                constraints = Constraints.Unconstrained,
                childPlans = emptyList(),
                inCheckerContext = inCheckerContext
            )
        )

        return QueryPlan(
            selectionSet = state.selectionSet,
            fragments = QueryPlan.Fragments(fragments.toMap()),
            variablesResolvers = variablesResolvers,
            parentType = parentType,
            childPlans = state.childPlans,
            attribution = attribution
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
        parentType: GraphQLCompositeType,
        field: GJField,
        state: State
    ): List<QueryPlan> =
        buildList {
            val resolverRsses = parameters.registry.getFieldResolverRequiredSelectionSets(parentType.name, field.name)
            addAll(buildChildPlansFromRequiredSelectionSets(resolverRsses, false))
            // Checker RSS's only depend on the raw slot
            if (!state.inCheckerContext) {
                val checkerRsses = parameters.registry.getFieldCheckerRequiredSelectionSets(parentType.name, field.name, parameters.executeAccessChecksInModstrat)
                addAll(buildChildPlansFromRequiredSelectionSets(checkerRsses, true))
            }
        }

    private fun buildFieldTypeChildPlans(
        fieldType: GraphQLNamedOutputType,
        state: State
    ): Map<GraphQLObjectType, List<QueryPlan>> {
        // Only checkers have type RSS's, so skip if we're in a checker context
        if (fieldType !is GraphQLCompositeType || state.inCheckerContext) {
            return emptyMap()
        }
        val possibleFieldTypes = parameters.schema.rels.possibleObjectTypes(fieldType)

        val fieldTypeChildPlanMap = mutableMapOf<GraphQLObjectType, List<QueryPlan>>()
        possibleFieldTypes.toList().forEach { it ->
            val requiredSelectionSets =
                parameters.registry.getTypeCheckerRequiredSelectionSets(it.name, parameters.executeAccessChecksInModstrat)
            val childPlans = buildChildPlansFromRequiredSelectionSets(requiredSelectionSets, true)
            if (childPlans.isNotEmpty()) {
                fieldTypeChildPlanMap[it] = childPlans
            }
        }
        return fieldTypeChildPlanMap
    }

    private fun buildChildPlansFromRequiredSelectionSets(
        requiredSelectionSets: List<RequiredSelectionSet>,
        inCheckerContext: Boolean
    ): List<QueryPlan> {
        if (requiredSelectionSets.isEmpty()) {
            return emptyList()
        }
        return requiredSelectionSets.map { rss ->
            // Use the typeName from ParsedSelections to determine correct target type
            val targetType = parameters.schema.schema.getType(rss.selections.typeName) as GraphQLCompositeType
            QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers)
                .createQueryPlan(
                    rss.selections.selections,
                    targetType,
                    attribution = rss.attribution,
                    inCheckerContext = inCheckerContext
                )
        }
    }

    /** Build a QueryPlan for each variable referenced by a field */
    private fun buildVariablesPlans(
        field: GJField,
        state: State
    ): List<QueryPlan> {
        val varRefs = field.collectVariableReferences()
        if (varRefs.isEmpty()) return emptyList()

        return varRefs.mapNotNull { varRef ->
            val vResolver = variableToResolver[varRef] ?: return@mapNotNull null

            // if the variable resolver has a required selection set, build a QueryPlan for that selection set
            vResolver.requiredSelectionSet?.let { rss ->
                QueryPlanBuilder(parameters, rss.selections.fragmentMap, rss.variablesResolvers)
                    .createQueryPlan(
                        rss.selections.selections,
                        parentType = parameters.schema.schema.getTypeAs(rss.selections.typeName),
                        attribution = rss.attribution,
                        inCheckerContext = state.inCheckerContext
                    )
            }
        }
    }

    private fun processField(
        sel: GJField,
        state: State
    ): State =
        with(state) {
            val coord = (state.parentType.name to sel.name).gj
            val fieldDef = parameters.schema.schema.getFieldDefinition(coord)
            val fieldType = GraphQLTypeUtil.unwrapAll(fieldDef.type) as GraphQLNamedOutputType

            val possibleParentTypes = parameters.schema.rels.possibleObjectTypes(parentType)

            val fieldChildPlans = buildRequiredSelectionSetPlans(parentType, sel, state)
            val planChildPlans = buildVariablesPlans(sel, state)

            val fieldTypeChildPlans = buildFieldTypeChildPlans(fieldType, state)

            val fieldConstraints = constraints
                .withDirectives(sel.directives)
                .narrowTypes(possibleParentTypes)

            if (fieldConstraints.solve(Constraints.Ctx.empty) == Constraints.Resolution.Drop) {
                return state
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
                    )
                )
            }

            val field = Field(
                resultKey = sel.resultKey,
                constraints = fieldConstraints,
                field = sel,
                selectionSet = subSelectionState?.selectionSet,
                childPlans = fieldChildPlans,
                fieldTypeChildPlans = fieldTypeChildPlans
            )

            state.copy(
                selectionSet = selectionSet + field,
                childPlans = childPlans + planChildPlans
            )
        }

    private fun processInlineFragment(
        sel: GJInlineFragment,
        state: State
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
                )
            )

            val inlineFragment = QueryPlan.InlineFragment(fragmentResult.selectionSet, newConstraints)
            copy(selectionSet = selectionSet + inlineFragment)
        }

    private fun processFragmentSpread(
        sel: GJFragmentSpread,
        state: State
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
                        childPlans = emptyList(),
                        inCheckerContext = state.inCheckerContext
                    )
                )
                fragments[name] = QueryPlan.FragmentDefinition(fragState.selectionSet)
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
            )
        }

    private fun processFragment(
        gjSelectionSet: GJSelectionSet,
        typeConditionName: String,
        state: State
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
            )
        )
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
