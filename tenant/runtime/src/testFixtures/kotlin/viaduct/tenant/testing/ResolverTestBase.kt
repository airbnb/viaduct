package viaduct.tenant.testing

import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import viaduct.api.FieldValue
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ObjectBaseTestHelpers
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.internal
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockFieldExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockMutationFieldExecutionContext
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.TestingApi
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.RawSelectionsLoaderImpl
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetImpl
import viaduct.tenant.runtime.select.SelectionsLoaderImpl

/**
 * Base class for Viaduct resolver tests. Use [runFieldResolver] for non-mutation resolvers
 * or [runMutationFieldResolver] for mutation resolvers to execute the resolver you want to test.
 * These utility functions call the resolve function with the correct Context parameter.
 *
 * This class provides core resolver testing utilities without assumptions about
 * dependency injection frameworks. Subclasses should provide their own injector
 * integration as needed.
 *
 * ## Integration
 *
 * Users need to provide a ViaductSchema implementation via [getSchema]
 *
 * For the schema, you can either:
 * - Load from a GraphQL schema file using ViaductSchemaGenerator.makeUnexecutableSchema()
 *
 * Example subclass:

 *    class MyResolverTestBase : ResolverTestBase() {
 *        override val selectionsLoaderFactory by lazy {
 *            mkSelectionsLoaderFactory()
 *        }
 *
 *        override val context: ExecutionContext by lazy {
 *            ResolverExecutionContextImpl(
 *               mkInternalContext(),
 *               queryLoader = mkQueryLoader(),
 *               selectionSetFactory = mkSelectionSetFactory(),
 *               nodeReferenceFactory = mkNodeReferenceFactory()
 *           )
 *        }
 *
 *        override fun getSchema(): ViaductSchema = myTestSchema
 *        override fun getFragmentLoader(): FragmentLoader = myMockFragmentLoader
 *    }
 *
 * Example usage:
 *
 *    // Mock the required selection set result
 *    val objectValue = Wishlist.Builder(context)
 *       .internalName("Hawaii")
 *       .namePhrase(null)
 *       .build()
 *
 *    val result = runFieldResolver(
 *       resolver,
 *       objectValue,
 *       Wishlist_Name_Arguments(...)
 *    )
 *    assertEquals("Hawaii", result)
 */
@OptIn(TestingApi::class)
interface ResolverTestBase {
    /**
     * An ExecutionContext that can be used to construct a builder, e.g. Foo.Builder(context).
     * This cannot be passed as the `ctx` param to the `resolve` function of a resolver, since
     * that's a subclass unique to the resolver.
     **/
    val context: ExecutionContext

    /**
     * Subclasses must provide the schema instance. This allows different implementations
     * to load the schema in their preferred way (e.g., from resources, test data, etc.)
     */
    fun getSchema(): ViaductSchema

    /**
     * Subclasses must provide a FragmentLoader instance. This allows different implementations
     * to integrate with their dependency injection framework.
     */
    fun getFragmentLoader(): FragmentLoader = mockk()

    val selectionsLoaderFactory: SelectionsLoader.Factory

    val ossSelectionSetFactory: SelectionSetFactory

    /**
     * Calls the resolve function for the given field [resolver].
     * Use this for non-mutation field resolvers. For mutation field resolvers, use [runMutationFieldResolver] instead.
     *
     * @param resolver The resolver to execute
     * @param objectValue The value of `ctx.objectValue` -- the result of the required selection set defined in @Resolver
     * @param queryValue The value of `ctx.queryValue` -- the query value
     * @param arguments The value of `ctx.arguments` -- the field arguments
     * @param selections The value of `ctx.selections()` -- the selection set of the field
     * @param contextQueryValues List of Query objects to mock results from ctx.query()
     * @return The return value of resolver.resolve()
     */
    suspend fun <T> runFieldResolver(
        resolver: ResolverBase<T>,
        objectValue: Object = NullObject,
        queryValue: Query = NullQuery,
        arguments: Arguments = Arguments.NoArguments,
        requestContext: Any? = null,
        selections: SelectionSet<*> = SelectionSet.NoSelections,
        contextQueryValues: List<Query> = emptyList()
    ): T {
        try {
            val ctxKClass = getFieldResolverContextKClass(resolver)
            val ctx = createFieldResolverContext(ctxKClass, objectValue, queryValue, arguments, requestContext, selections, contextQueryValues)
            @Suppress("UNCHECKED_CAST")
            return resolver::class.declaredFunctions.first { it.name == "resolve" }.callSuspend(resolver, ctx) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Calls the resolve function for the given mutation field [resolver].
     * Use this for mutation field resolvers. For non-mutation field resolvers, use [runFieldResolver] instead.
     *
     * @param resolver The resolver to execute
     * @param queryValue The value of `ctx.queryValue` -- the query value
     * @param arguments The value of `ctx.arguments` -- the field arguments
     * @param selections The value of `ctx.selections()` -- the selection set of the field
     * @param contextQueryValues List of Query objects to mock results from ctx.query()
     * @param contextMutationValues List of Mutation objects to mock results from ctx.mutation()
     * @return The return value of resolver.resolve()
     */
    suspend fun <T> runMutationFieldResolver(
        resolver: ResolverBase<T>,
        queryValue: Query = NullQuery,
        arguments: Arguments = Arguments.NoArguments,
        requestContext: Any? = null,
        selections: SelectionSet<*> = SelectionSet.NoSelections,
        contextQueryValues: List<Query> = emptyList(),
        contextMutationValues: List<Mutation> = emptyList()
    ): T {
        try {
            val ctxKClass = getMutationFieldResolverContextKClass(resolver)
            val ctx = createMutationFieldResolverContext(ctxKClass, queryValue, arguments, requestContext, selections, contextQueryValues, contextMutationValues)
            @Suppress("UNCHECKED_CAST")
            return resolver::class.declaredFunctions.first { it.name == "resolve" }.callSuspend(resolver, ctx) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Calls the batchResolve function for the given field [resolver].
     * Use this for non-mutation field resolvers that use [FieldExecutionContext].
     *
     * @param resolver The resolver to execute
     * @param objectValues The values of `ctx.objectValue` -- the result of the required selection set defined in @Resolver
     * @param queryValues The values of `ctx.queryValue` -- the query values
     * @param selections The value of `ctx.selections()` -- the selection set of the field
     * @param contextQueryValues List of Query objects to mock results from ctx.query()
     * @return The return value of resolver.resolve()
     */
    suspend fun <T> runFieldBatchResolver(
        resolver: ResolverBase<T>,
        objectValues: List<Object> = listOf<Object>(),
        queryValues: List<Query> = objectValues.map { NullQuery },
        requestContext: ExecutionContext? = null,
        selections: SelectionSet<*>? = null,
        contextQueryValues: List<Query> = emptyList()
    ): List<FieldValue<T>> {
        try {
            require(objectValues.size == queryValues.size) {
                "objectValues and queryValues must have the same size: objectValues.size=${objectValues.size}, queryValues.size=${queryValues.size}"
            }
            val ctxKClass = getFieldResolverContextKClass(resolver)
            val ctxs = objectValues.zip(queryValues) { obj, query ->
                createFieldResolverContext(
                    ctxKClass,
                    obj,
                    query,
                    Arguments.NoArguments /* do not support batch field with arguments yet */,
                    requestContext,
                    selections ?: mockk<SelectionSet<*>>(),
                    contextQueryValues
                )
            }
            @Suppress("UNCHECKED_CAST")
            return resolver::class.declaredFunctions.first { it.name == "batchResolve" }.callSuspend(resolver, ctxs) as List<FieldValue<T>>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Calls the resolve function for the given node [resolver]
     *
     * @param resolver The resolver to execute
     * @param id The value of `ctx.id` -- the node's ID
     * @param selections The value of `ctx.selections()` -- the selection set of the node field
     * @return The return value of resolver.resolve()
     */
    suspend fun <T : NodeObject> runNodeResolver(
        resolver: NodeResolverBase<T>,
        id: GlobalID<T>,
        requestContext: Any? = null,
        selections: SelectionSet<T>? = null,
        contextQueryValues: List<Query> = emptyList()
    ): T {
        try {
            val ctxKClass = getNodeResolverContextKClass(resolver)
            val ctx = createNodeResolverContext(ctxKClass, id, requestContext, selections ?: mockk<SelectionSet<T>>(), contextQueryValues)
            @Suppress("UNCHECKED_CAST")
            return resolver::class.declaredFunctions.first { it.name == "resolve" }.callSuspend(resolver, ctx) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Calls the batchResolve function for the given node [resolver]
     *
     * @param resolver The resolver to execute
     * @param ids The value of `id` in each context in `contexts` -- the node IDs
     * @param selections The value of `selections()` -- the selection set of the node field. If you want to use different
     *        selections, use [createNodeResolverContext] instead to construct individual Context objects
     * @return The return value of resolver.batchResolve()
     */
    suspend fun <T : NodeObject> runNodeBatchResolver(
        resolver: NodeResolverBase<T>,
        ids: List<GlobalID<T>>,
        requestContext: Any? = null,
        selections: SelectionSet<T>? = null,
        contextQueryValues: List<Query> = emptyList()
    ): List<FieldValue<T>> {
        try {
            val ctxKClass = getNodeResolverContextKClass(resolver)
            val ctxs = ids.map {
                createNodeResolverContext(ctxKClass, it, requestContext, selections ?: mockk<SelectionSet<T>>(), contextQueryValues)
            }
            @Suppress("UNCHECKED_CAST")
            return resolver::class.declaredFunctions.first { it.name == "batchResolve" }.callSuspend(resolver, ctxs) as List<FieldValue<T>>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Use this to construct a GlobalID in your test
     */
    fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = context.globalIDFor(type, internalID)

    /**
     * Builds a map of selection set representations to Query objects
     */
    fun buildContextQueryMap(contextQueries: List<Query>): PrebakedResults<Query> =
        buildContextMap(
            contextValues = contextQueries,
            rootTypeName = getSchema().schema.queryType.name,
            typeName = "Query",
            wrapperClass = QueryForSelection::class,
            extractSelection = { (it as QueryForSelection).selections },
            extractValue = { (it as QueryForSelection).query }
        )

    /**
     * Builds a map of selection set representations to Mutation objects
     */
    fun buildContextMutationMap(contextMutations: List<Mutation>): PrebakedResults<Mutation> =
        buildContextMap(
            contextValues = contextMutations,
            rootTypeName = getSchema().schema.mutationType.name,
            typeName = "Mutation",
            wrapperClass = MutationForSelection::class,
            extractSelection = { (it as MutationForSelection).selections },
            extractValue = { (it as MutationForSelection).mutation }
        )

    private fun <T : CompositeOutput> buildContextMap(
        contextValues: List<T>,
        rootTypeName: String,
        typeName: String,
        wrapperClass: KClass<*>,
        extractSelection: (T) -> String,
        extractValue: (T) -> T
    ): PrebakedResults<T> {
        if (contextValues.isEmpty()) return prebakedResultsOf(emptyMap())

        val unselectedValues = contextValues.filterNot { wrapperClass.isInstance(it) }
        if (unselectedValues.size > 1) {
            throw IllegalArgumentException(
                "Cannot have multiple $typeName values in context${typeName}s. " +
                    "Please provide a single $typeName or use ${wrapperClass.simpleName} to distinguish results."
            )
        }

        val rl = context.internal.reflectionLoader

        @Suppress("UNCHECKED_CAST")
        val rootType = rl.reflectionFor(rootTypeName) as Type<T>
        val resultMap = mutableMapOf<String, T>()
        contextValues.forEach { rawValue ->
            val (selectionSet, value) = if (wrapperClass.isInstance(rawValue)) {
                val selSet = ossSelectionSetFactory.selectionsOn(rootType, extractSelection(rawValue), emptyMap())
                Pair(selSet, extractValue(rawValue))
            } else {
                Pair(SelectionSet.NoSelections, rawValue)
            }
            resultMap[createSelectionSetKey(selectionSet)] = value
        }
        return prebakedResultsOf(resultMap)
    }

    /**
     * Creates a VariablesProviderContext for the given arguments. Used for testing resolvers that make use of a VariablesProvider.
     */
    fun <T : Arguments> createVariablesProviderContext(arguments: T): VariablesProviderContext<T> {
        return object :
            VariablesProviderContext<T>,
            ExecutionContext by context,
            InternalContext by context.internal {
            override val arguments: T = arguments
        }
    }

    fun <T : NodeObject> ResolverTestBase.createNodeResolverContext(
        ctxKClass: KClass<out NodeExecutionContext<T>>,
        id: GlobalID<T>,
        requestContext: Any? = null,
        selections: SelectionSet<T> = mockk<SelectionSet<T>>(),
        contextQueryValues: List<Query> = emptyList()
    ): NodeExecutionContext<T> {
        val innerCtx = mkNodeExecutionContext(id, selections, contextQueryValues)
        return ctxKClass.primaryConstructor?.call(innerCtx) ?: innerCtx
    }

    fun ResolverTestBase.createFieldResolverContext(
        ctxKClass: KClass<out FieldExecutionContext<*, *, *, *>>,
        objectValue: Object = NullObject,
        queryValue: Query = NullQuery,
        arguments: Arguments = Arguments.NoArguments,
        requestContext: Any? = null,
        selections: SelectionSet<*> = SelectionSet.NoSelections,
        contextQueries: List<Query> = emptyList()
    ): FieldExecutionContext<*, *, *, *> {
        val innerCtx = mkFieldExecutionContext(
            objectValue,
            queryValue,
            arguments,
            requestContext,
            selections,
            contextQueries
        )
        // Primary constructor is null when Ctx is FieldExecutionContext
        return ctxKClass.primaryConstructor?.call(innerCtx) ?: innerCtx
    }

    fun ResolverTestBase.createMutationFieldResolverContext(
        ctxKClass: KClass<out MutationFieldExecutionContext<*, *, *>>,
        queryValue: Query = NullQuery,
        arguments: Arguments = Arguments.NoArguments,
        requestContext: Any? = null,
        selections: SelectionSet<*> = SelectionSet.NoSelections,
        contextQueries: List<Query> = emptyList(),
        contextMutations: List<Mutation> = emptyList()
    ): MutationFieldExecutionContext<*, *, *> {
        val innerCtx = mkMutationFieldExecutionContext(
            queryValue,
            arguments,
            requestContext,
            selections,
            contextQueries,
            contextMutations
        )
        // Primary constructor is null when Ctx is MutationFieldExecutionContext
        return ctxKClass.primaryConstructor?.call(innerCtx) ?: innerCtx
    }

    fun mkExecutionContext(): ExecutionContext {
        val rl = mockReflectionLoader("viaduct.api.grts")
        val internal = MockInternalContext(getSchema(), GlobalIDCodecImpl(rl), rl)
        return MockExecutionContext(internal)
    }

    fun mkSelectionsLoaderFactory(): SelectionsLoader.Factory =
        SelectionsLoaderImpl.Factory(
            RawSelectionsLoaderImpl.Factory(
                getFragmentLoader(),
                getSchema()
            )
        )

    fun mkSelectionSetFactory(): SelectionSetFactory =
        SelectionSetFactoryImpl(
            RawSelectionSetFactoryImpl(getSchema())
        )

    /**
     * Helper function to add a field with an alias to an ObjectBase.Builder.
     * This is useful for testing resolvers that return fields with aliases.
     */
    fun <R, T : ObjectBase.Builder<R>> T.put(
        name: String,
        value: Any?,
        alias: String
    ): T {
        return ObjectBaseTestHelpers.putWithAlias(
            builder = this,
            name = name,
            value = value,
            alias = alias
        )
    }
}

// Internal helper functions and values
private const val BLANK_CONTEXT_QUERY_SELECTION_KEY = "NoSelections"

private fun <T : NodeObject> getNodeResolverContextKClass(resolver: NodeResolverBase<T>): KClass<out NodeExecutionContext<T>> {
    val nestedClasses = resolver.javaClass.classes
    val contextClass = nestedClasses.firstOrNull { it.simpleName == "Context" }
        ?: throw IllegalArgumentException(
            "Expected resolver (${resolver::class.qualifiedName}) to contain a nested class called 'Context', but none was found."
        )
    @Suppress("UNCHECKED_CAST")
    return contextClass.kotlin as? KClass<out NodeExecutionContext<T>>
        ?: throw IllegalArgumentException(
            "Expected resolver (${resolver::class.qualifiedName}) Context class (${contextClass.kotlin.qualifiedName}) to implement NodeExecutionContext."
        )
}

private fun <T : NodeObject> ResolverTestBase.mkNodeExecutionContext(
    id: GlobalID<T>,
    selections: SelectionSet<T>,
    requestContext: Any? = null,
    contextQueryValues: List<Query> = emptyList()
): NodeExecutionContext<T> {
    val internalContext = context.internal
    val queryResultsMap = buildContextQueryMap(contextQueryValues)

    return MockNodeExecutionContext(
        id = id,
        selectionsValue = selections,
        internalContext = internalContext,
        queryResults = queryResultsMap,
        selectionSetFactory = ossSelectionSetFactory,
    )
}

private inline fun <T, reified C : Any> getResolverContextKClass(resolver: ResolverBase<T>): KClass<out C> {
    val nestedClasses = resolver.javaClass.classes
    val contextClass = nestedClasses.firstOrNull { it.simpleName == "Context" }
        ?: throw IllegalArgumentException(
            "Expected resolver (${resolver::class.qualifiedName}) to contain a nested class called 'Context', but none was found."
        )

    @Suppress("UNCHECKED_CAST")
    return contextClass.kotlin as? KClass<out C>
        ?: throw IllegalArgumentException(
            "Expected resolver (${resolver::class.qualifiedName}) Context class (${contextClass.kotlin.qualifiedName}) to implement ${C::class.simpleName}."
        )
}

private fun <T> getFieldResolverContextKClass(resolver: ResolverBase<T>): KClass<out FieldExecutionContext<*, *, *, *>> = getResolverContextKClass(resolver)

private fun <T> getMutationFieldResolverContextKClass(resolver: ResolverBase<T>): KClass<out MutationFieldExecutionContext<*, *, *>> = getResolverContextKClass(resolver)

/**
 * Creates a Context class for a specific node resolver. We suggest using [runNodeResolver] to test the
 * resolve function, but this can be useful if you want to test other functions that take Context as a parameter.
 *
 * @param ctx The type of the Context class being created, which is nested in the generated base class for the
 *        resolver. As always, the type parameter can be omitted if it can be inferred by the compiler, e.g.
 *        `someFunction(createNodeResolverContext(...))`. If it can't be inferred, you can specify it like so for a node Foo:
 *        `val ctx = createNodeResolverContext<FooResolver.Context>(...)`
 */
private inline fun <T : NodeObject, reified ctx : NodeExecutionContext<T>> ResolverTestBase.createNodeResolverContext(
    id: GlobalID<T>,
    requestContext: Any? = null,
    selections: SelectionSet<T> = mockk<SelectionSet<T>>(),
    contextQueryValues: List<Query> = emptyList()
): ctx = createNodeResolverContext(ctx::class, id, requestContext, selections, contextQueryValues) as ctx

/**
 * Creates a Context class for a specific field resolver. We suggest using [runFieldResolver] to test the
 * resolve function, but this can be useful if you want to test other functions that take Context as a parameter.
 *
 * @param ctx The type of the Context class being created, which is nested in the generated base class for the
 *        resolver. As always, the type parameter can be omitted if it can be inferred by the compiler, e.g.
 *        `someFunction(createResolverContext(...))`. If it can't be inferred, you can specify it like so for a field Foo.bar:
 *        `val ctx = createResolverContext<FooResolver.Bar.Context>(...)`
 */
inline fun <reified ctx : FieldExecutionContext<*, *, *, *>> ResolverTestBase.createResolverContext(
    objectValue: Object = NullObject,
    queryValue: Query = NullQuery,
    arguments: Arguments = Arguments.NoArguments,
    requestContext: Any? = null,
    selections: SelectionSet<*> = SelectionSet.NoSelections,
    contextQueries: List<Query> = emptyList()
): ctx = createFieldResolverContext(ctx::class, objectValue, queryValue, arguments, requestContext, selections, contextQueries) as ctx

private fun ResolverTestBase.mkFieldExecutionContext(
    objectValue: Object,
    queryValue: Query,
    arguments: Arguments,
    requestContext: Any?,
    selections: SelectionSet<*>,
    contextQueryValues: List<Query> = emptyList()
): FieldExecutionContext<*, *, *, *> {
    val internalContext = context.internal
    val queryResultsMap = buildContextQueryMap(contextQueryValues)

    return MockFieldExecutionContext(
        objectValue = objectValue,
        queryValue = queryValue,
        arguments = arguments,
        requestContext = requestContext,
        selectionsValue = selections,
        internalContext = internalContext,
        queryResults = queryResultsMap,
        selectionSetFactory = ossSelectionSetFactory,
    )
}

private fun ResolverTestBase.mkMutationFieldExecutionContext(
    queryValue: Query,
    arguments: Arguments,
    requestContext: Any?,
    selections: SelectionSet<*>,
    contextQueryValues: List<Query> = emptyList(),
    contextMutationValues: List<Mutation> = emptyList()
): MutationFieldExecutionContext<*, *, *> {
    val internalContext = context.internal
    val queryResultsMap = buildContextQueryMap(contextQueryValues)
    val mutationResultsMap = buildContextMutationMap(contextMutationValues)

    return MockMutationFieldExecutionContext(
        queryValue = queryValue,
        arguments = arguments,
        requestContext = requestContext,
        selectionsValue = selections,
        internalContext = internalContext,
        queryResults = queryResultsMap,
        mutationResults = mutationResultsMap,
        selectionSetFactory = ossSelectionSetFactory,
    )
}

/**
 * Creates a consistent string key for a SelectionSet that can be used for map lookups.
 * This ensures SelectionSets with the same content but different object references
 * will have the same key.
 */
private fun createSelectionSetKey(selectionSet: SelectionSet<*>): String {
    return when (selectionSet) {
        is SelectionSetImpl -> selectionSet.rawSelectionSet.printAsFieldSet()
        SelectionSet.NoSelections -> BLANK_CONTEXT_QUERY_SELECTION_KEY
        else -> selectionSet.toString()
    }
}

object NullObject : Object

object NullQuery : Query

/**
 * Wrapper around a [Query] that is used to distinguish between different selections in the context query map.
 * Pass in one (or more) of these to [runFieldResolver], [runNodeResolver], and the other related methods
 * to mock out the query results for a specific selection set.
 */
class QueryForSelection(
    val selections: String,
    val query: Query
) : Query by query

/**
 * Wrapper around a [Mutation] that is used to distinguish between different selections in the context mutation map.
 * Pass in one (or more) of these to [runMutationFieldResolver] to mock out the mutation results for a specific selection set.
 */
class MutationForSelection(
    val selections: String,
    val mutation: Mutation
) : Mutation by mutation

@Suppress("USELESS_CAST")
private fun <T : CompositeOutput> prebakedResultsOf(results: Map<String, T>) =
    object : PrebakedResults<T> {
        override fun get(selections: SelectionSet<T>): T {
            if (results.isEmpty()) {
                throw IllegalArgumentException(
                    "No mocked results provided for suboperations (i.e., ctx.query/mutation). Please provide at least one result."
                )
            }

            val key = createSelectionSetKey(selections)

            // If not found and there's a NoSelections entry, use that as a fallback
            // This handles the case where a single unnamed Query was provided in contextQueryValues
            val result = results[key] ?: results[BLANK_CONTEXT_QUERY_SELECTION_KEY]

            if (result == null) {
                throw IllegalArgumentException(
                    "No mocked results provided for selections: '$key'. Available keys: ${results.keys}"
                )
            }

            return result as T
        }
    }
