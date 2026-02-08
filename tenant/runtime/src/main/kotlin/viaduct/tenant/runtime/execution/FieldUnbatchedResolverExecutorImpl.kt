package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.ResolverBase
import viaduct.api.wrapResolveException
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.FieldResolverExecutor.Selector
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory

/**
 * Executes a tenant-written field resolver's batchResolve function.
 *
 * @param resolverId: Uniquely identifies a resolver function, e.g. "User.fullName" identifies
 * the field resolver for the "fullName" field on the "User" type. This is used for observability.
 */
class FieldUnbatchedResolverExecutorImpl(
    override val objectSelectionSet: RequiredSelectionSet?,
    override val querySelectionSet: RequiredSelectionSet?,
    val resolver: Provider<out @JvmSuppressWildcards ResolverBase<*>>,
    private val resolveFn: KFunction<*>,
    override val resolverId: String,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    private val resolverContextFactory: FieldExecutionContextFactory,
    private val resolverName: String,
) : FieldResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName)

    override val isBatching = false

    override suspend fun batchResolve(
        selectors: List<Selector>,
        context: EngineExecutionContext
    ): Map<Selector, Result<Any?>> {
        // Only handle single selector case because this is an unbatched resolver
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got ${selectors.size}" }
        val selector = selectors.first()
        return mapOf(selector to runCatching { resolve(selector, context) })
    }

    private suspend fun resolve(
        selector: Selector,
        context: EngineExecutionContext,
    ): Any? {
        val ctx = resolverContextFactory(
            engineExecutionContext = context,
            requestContext = context.requestContext, // TODO - get rid of this argument
            rawSelections = selector.selections,
            rawArguments = selector.arguments,
            rawObjectValue = selector.objectValue,
            rawQueryValue = selector.queryValue,
            syncObjectValueGetter = selector.syncObjectValueGetter,
            syncQueryValueGetter = selector.syncQueryValueGetter,
        )
        val resolver = mkResolver()
        val result = wrapResolveException(resolverId) {
            resolveFn.callSuspend(resolver, ctx)
        }
        return unwrapFieldResolverResult(result, globalIDCodec)
    }

    private fun mkResolver(): ResolverBase<*> = resolver.get()

    companion object {
        internal fun unwrapFieldResolverResult(
            result: Any?,
            globalIDCodec: GlobalIDCodec
        ): Any? {
            return when (result) {
                is ObjectBase -> result.engineObject
                is List<*> -> result.map { unwrapFieldResolverResult(it, globalIDCodec) }
                is GlobalID<*> -> globalIDCodec.serialize(result.type.name, result.internalID)
                is Enum<*> -> result.name
                else -> result
            }
        }
    }
}
