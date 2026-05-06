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
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.types.Arguments

/**
 * Kotlin bridge that wraps a batch Java field resolver and implements [FieldResolverExecutor].
 *
 * Called when [FieldResolverExecutor.isBatching] is true. Receives all selectors for a field
 * in a single call, creates per-selector contexts, invokes the tenant's
 * `batchResolve(List<Context>): CompletableFuture<Map<Context, T>>`, and maps the results back
 * to the engine's selector-keyed format.
 */
class JavaFieldBatchResolverExecutor(
    private val batchResolveFunction: (List<FieldExecutionContext<*, *, *, *>>) -> CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, *>>,
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
    override val isBatching: Boolean = true

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        val scope = CoroutineScope(currentCoroutineContext())

        // Build one typed context per selector
        val javaContexts: List<FieldExecutionContext<*, *, *, *>> = selectors.map { selector ->
            val arguments = handleFrameworkErrors("$resolverId: createArguments") {
                createArguments(selector.arguments)
            }
            val objectValue = handleFrameworkErrorsSuspend("$resolverId: createObjectValue") {
                createObjectValue(selector)
            }
            val queryValue = handleFrameworkErrorsSuspend("$resolverId: createQueryValue") {
                createQueryValue(selector)
            }
            SimpleFieldExecutionContext(
                requestContext = context.requestContext,
                arguments = arguments,
                objectValue = objectValue,
                queryValue = queryValue,
                engineExecutionContext = context,
                coroutineScope = scope,
            )
        }

        // Call the tenant's batchResolve; result is Map<FieldExecutionContext, T>
        // invokeBatchResolver remaps Context keys to their inner FieldExecutionContext so we can do
        // deterministic key-based lookup regardless of the Map type returned by the tenant.
        val rawResult: Map<FieldExecutionContext<*, *, *, *>, *> = handleTenantErrorsSuspend(resolverId) {
            val future = batchResolveFunction(javaContexts)
            future.await()
        }

        if (rawResult.size != selectors.size) {
            throw TenantUsageException(
                "batchResolve for $resolverId was given ${selectors.size} contexts but returned ${rawResult.size} entries"
            )
        }

        // javaContexts[i] is keyed in rawResult; look up each context to get its value,
        // wrapping each conversion in resultOfSuspend so a per-item failure becomes
        // Result.failure for that selector rather than aborting the entire batch.
        val out = mutableMapOf<FieldResolverExecutor.Selector, Result<Any?>>()
        selectors.zip(javaContexts).forEach { (selector, javaCtx) ->
            out[selector] = resultOfSuspend {
                if (!rawResult.containsKey(javaCtx)) {
                    throw TenantUsageException(
                        "batchResolve for $resolverId did not return a result for context at index ${javaContexts.indexOf(javaCtx)}"
                    )
                }
                val value = rawResult[javaCtx]
                handleFrameworkErrors("$resolverId: convertResult") {
                    convertResult(value, graphqlSchema)
                }
            }
        }
        return out
    }

    private fun createArguments(argumentMap: Map<String, Any?>): Arguments? {
        if (argumentsClass == null || argumentsClass == Arguments.None::class.java) return null
        @Suppress("UNCHECKED_CAST")
        val constructor = argumentsClass.getDeclaredConstructor(Map::class.java)
        return constructor.newInstance(argumentMap) as Arguments
    }

    private suspend fun createObjectValue(selector: FieldResolverExecutor.Selector): Any? {
        if (objectValueClass == null) return null
        val syncGetter = selector.syncObjectValueGetter ?: return null
        return convertSyncEngineDataToJavaObject(objectValueClass, syncGetter())
    }

    private suspend fun createQueryValue(selector: FieldResolverExecutor.Selector): Any? {
        if (queryValueClass == null) return null
        val syncGetter = selector.syncQueryValueGetter ?: return null
        return convertSyncEngineDataToJavaObject(queryValueClass, syncGetter())
    }
}
