package viaduct.java.runtime.bridge

import graphql.schema.GraphQLSchema
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.types.Arguments

/**
 * Kotlin bridge that wraps a Java resolver and implements [FieldResolverExecutor]
 * for the Viaduct engine.
 *
 * This bridge converts between:
 * - Java CompletableFuture <-> Kotlin suspend functions
 * - Java FieldExecutionContext <-> Kotlin EngineExecutionContext
 * - Engine argument maps <-> Typed Java Arguments instances
 *
 * @param resolveFunction A function that takes a FieldExecutionContext and returns a CompletableFuture
 * @param resolverId Unique identifier for this resolver (e.g., "Query.greeting")
 * @param resolverName Human-readable resolver name for metadata
 * @param argumentsClass The Java Arguments class for this resolver, used to create typed instances
 *        from the engine's argument map. Null if the resolver takes no arguments.
 * @param objectValueClass The Java class for the parent object type (e.g., Person.class for a
 *        Person.fullAddress resolver). Used to convert engine object data to a typed Java instance
 *        when the resolver declares an objectValueFragment. Null if the resolver doesn't need
 *        object value access.
 * @param queryValueClass The Java class for the Query GRT type. Used to convert engine query data
 *        to a typed Java instance when the resolver declares a queryValueFragment. Null if the
 *        resolver doesn't use query value access.
 * @param graphqlSchema The GraphQL schema, used to look up object types when converting Java GRT
 *        objects returned by resolvers back into EngineObjectData for the engine.
 */
class JavaFieldResolverExecutorImpl(
    private val resolveFunction: (FieldExecutionContext<*, *, *, *>) -> CompletableFuture<*>,
    override val resolverId: String,
    private val resolverName: String,
    private val argumentsClass: Class<out Arguments>? = null,
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    override val isSelective: Boolean = false,
    private val objectValueClass: Class<*>? = null,
    private val queryValueClass: Class<*>? = null,
    private val graphqlSchema: GraphQLSchema? = null,
) : FieldResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.FIELD)
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        // Unbatched resolver only handles single selector
        require(selectors.size == 1) {
            "Unbatched Java resolver should only receive single selector, got ${selectors.size}"
        }

        val selector = selectors.first()
        val result = resultOfSuspend {
            resolveOne(selector = selector, context = context)
        }

        return mapOf(selector to result)
    }

    private suspend fun resolveOne(
        selector: FieldResolverExecutor.Selector,
        context: EngineExecutionContext,
    ): Any? {
        // ── Framework→Tenant boundary: context setup ──
        val arguments = handleFrameworkErrors("$resolverId: createArguments") {
            createArguments(selector.arguments)
        }
        val objectValue = handleFrameworkErrorsSuspend("$resolverId: createObjectValue") {
            createObjectValue(selector)
        }
        val queryValue = handleFrameworkErrorsSuspend("$resolverId: createQueryValue") {
            createQueryValue(selector)
        }
        val scope = CoroutineScope(currentCoroutineContext())

        val javaContext = SimpleFieldExecutionContext(
            requestContext = context.requestContext,
            arguments = arguments,
            objectValue = objectValue,
            queryValue = queryValue,
            engineExecutionContext = context,
            coroutineScope = scope,
        )

        // ── Tenant→Framework boundary: resolver call ──
        val result = handleTenantErrorsSuspend(resolverId) {
            val future = resolveFunction(javaContext)
            future.await()
        }

        // ── Framework→Tenant boundary: result conversion ──
        return handleFrameworkErrors("$resolverId: convertResult") {
            convertResult(result, graphqlSchema)
        }
    }

    /**
     * Creates a typed Java query root object from the engine's sync query value getter.
     *
     * When a resolver declares a queryValueFragment, the engine pre-resolves those selections and
     * provides them via the selector's syncQueryValueGetter. This method converts that engine data
     * into the typed Java query object so the resolver can access it via ctx.getQueryValue().
     *
     * Returns null if no queryValueClass is configured or no sync getter is available.
     */
    private suspend fun createQueryValue(selector: FieldResolverExecutor.Selector): Any? {
        if (queryValueClass == null) return null
        return convertSyncEngineDataToJavaObject(queryValueClass, selector.syncQueryValueGetter())
    }

    /**
     * Creates a typed Java object from the engine's object data using the syncObjectValueGetter.
     *
     * When a resolver declares an objectValueFragment (e.g., `@Resolver(objectValueFragment = "address { street city }")`),
     * the engine resolves those selections and provides them via the selector's syncObjectValueGetter.
     * This method converts that engine data into the typed Java object (e.g., Person) so the
     * resolver can access it via ctx.getObjectValue().
     *
     * Returns null if no objectValueClass is configured or no sync getter is available.
     */
    private suspend fun createObjectValue(selector: FieldResolverExecutor.Selector): Any? {
        if (objectValueClass == null) return null
        return convertSyncEngineDataToJavaObject(objectValueClass, selector.syncObjectValueGetter())
    }

    /**
     * Creates a typed Arguments instance from the engine's argument map.
     *
     * The arguments class must have a public constructor accepting Map<String, Any?> (generated
     * by the new wrapping-based codegen). No reflection over fields or setters.
     * Returns null if no arguments class is configured (resolver takes no arguments).
     */
    private fun createArguments(argumentMap: Map<String, Any?>): Arguments? {
        if (argumentsClass == null || argumentsClass == Arguments.None::class.java) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val constructor = argumentsClass.getDeclaredConstructor(Map::class.java)
        return constructor.newInstance(argumentMap) as Arguments
    }
}
