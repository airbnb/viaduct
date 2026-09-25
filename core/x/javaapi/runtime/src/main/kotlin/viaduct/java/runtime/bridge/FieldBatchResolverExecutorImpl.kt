package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.errors.ErroneousFieldException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.Arguments

/**
 * Kotlin bridge that wraps a batch Java field resolver and implements [FieldResolverExecutor].
 *
 * Called when [FieldResolverExecutor.isBatching] is true. Receives all selectors for a field
 * in a single call, creates per-selector contexts, invokes the tenant's
 * `batchResolveWithErrors(List<Context>): CompletableFuture<Map<Context, FieldValue<T>>>`, and maps the results back
 * to the engine's selector-keyed format.
 */
class FieldBatchResolverExecutorImpl(
    private val resolver: Provider<BaseBatchedFieldResolver>,
    override val resolverId: String,
    private val resolverName: String,
    private val argumentsClass: Class<out Arguments>? = null,
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    override val isSelective: Boolean = false,
    private val objectValueClass: Class<*>? = null,
    private val queryValueClass: Class<*>? = null,
    private val graphqlSchema: GraphQLSchema? = null,
    private val grtPackagePrefix: String? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
    override val argumentVariables: VariableFromArgumentDefinitions = VariableFromArgumentDefinitions.EMPTY,
) : FieldResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.FIELD)
    override val isBatching: Boolean = true

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext,
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        val scope = CoroutineScope(currentCoroutineContext())

        // Per-request InternalContext attached to GRTs and propagated to nested GRTs.
        val internalContext = buildInternalContext(context, grtPackagePrefix)

        // Build one typed context per selector
        val javaContexts: List<FieldExecutionContext<*, *, *, *>> = selectors.map { selector ->
            val arguments = handleFrameworkErrors("$resolverId: createArguments") {
                createArguments(selector.arguments, internalContext)
            }
            val objectValue = handleFrameworkErrorsSuspend("$resolverId: createObjectValue") {
                createObjectValue(selector, internalContext)
            }
            val queryValue = handleFrameworkErrorsSuspend("$resolverId: createQueryValue") {
                createQueryValue(selector, internalContext)
            }
            SimpleFieldExecutionContext(
                requestContext = context.requestContext,
                arguments = arguments,
                objectValue = objectValue,
                queryValue = queryValue,
                engineExecutionContext = context,
                coroutineScope = scope,
                grtPackagePrefix = grtPackagePrefix,
                knownFragments = knownFragments,
            )
        }

        val results: Map<FieldExecutionContext<*, *, *, *>, *> = handleTenantErrorsSuspend(resolverId) {
            val results = resolver.get().invokeFieldBatchResolverWithErrors(javaContexts).await()
                ?: throw TenantUsageException("batchResolve for $resolverId returned a null map")
            if (results.size != selectors.size) {
                throw TenantUsageException(
                    "batchResolve for $resolverId was given ${selectors.size} contexts but returned ${results.size} entries"
                )
            }
            if (javaContexts.any { !results.containsKey(it) }) {
                throw TenantUsageException("batchResolve for $resolverId returned a context that was not in the input context list")
            }
            results
        }

        return selectors.zip(javaContexts).associate { (selector, javaContext) ->
            selector to unwrap(results[javaContext])
        }
    }

    private suspend fun unwrap(fieldValue: Any?): Result<Any?> =
        resultOfSuspend(
            mapException = { error ->
                if (error is PassthroughException || error is ErroneousFieldException) {
                    error
                } else {
                    TenantResolverException(error, resolverId)
                }
            }
        ) {
            if (fieldValue !is FieldValue<*>) {
                throw TenantUsageException("batchResolve for $resolverId returned an invalid FieldValue: $fieldValue; use FieldValue.ofValue(null) for null")
            }
            val value = fieldValue.get()
            handleFrameworkErrors("$resolverId: convertResult") {
                convertResult(value, graphqlSchema)
            }
        }

    private fun createArguments(
        argumentMap: Map<String, Any?>,
        internalContext: InternalContext?
    ): Arguments? {
        if (
            argumentsClass == null ||
            Arguments.isNoArgumentsClass(argumentsClass)
        ) {
            return null
        }

        val graphQLInputObjectType: GraphQLInputObjectType? = internalContext?.let { ctx ->
            buildArgumentsInputType(argumentsClass, resolverId, ctx)
        }

        @Suppress("UNCHECKED_CAST")
        val constructor = argumentsClass.getDeclaredConstructor(
            InternalContext::class.java,
            Map::class.java,
            GraphQLInputObjectType::class.java
        )
        return constructor.newInstance(internalContext, argumentMap, graphQLInputObjectType) as Arguments
    }

    private suspend fun createObjectValue(
        selector: FieldResolverExecutor.Selector,
        internalContext: InternalContext?
    ): Any? {
        if (objectValueClass == null) return null
        return convertSyncEngineDataToJavaObject(objectValueClass, selector.syncObjectValueGetter(), internalContext)
    }

    private suspend fun createQueryValue(
        selector: FieldResolverExecutor.Selector,
        internalContext: InternalContext?
    ): Any? {
        if (queryValueClass == null) return null
        return convertSyncEngineDataToJavaObject(queryValueClass, selector.syncQueryValueGetter(), internalContext)
    }
}
