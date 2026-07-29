package viaduct.tenant.runtime.execution

import javax.inject.Provider
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldResolverExecutor.Selector
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory

/**
 * Executes a tenant-written field resolver's resolve function.
 *
 * @param resolverId: Uniquely identifies a resolver function, e.g. "User.fullName" identifies
 * the field resolver for the "fullName" field on the "User" type. This is used for observability.
 */
class FieldUnbatchedResolverExecutorImpl(
    override val objectSelectionSet: RequiredSelectionSet?,
    override val querySelectionSet: RequiredSelectionSet?,
    override val isSelective: Boolean,
    val resolver: Provider<out @JvmSuppressWildcards BaseUnbatchedFieldResolver>,
    override val resolverId: String,
    private val resolverContextFactory: FieldExecutionContextFactory,
    private val resolverName: String,
    private val tenantMetadata: TenantModuleMetadata? = null,
) : FieldResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName, ResolverType.FIELD, tenantMetadata)

    override val isBatching = false

    override suspend fun batchResolve(
        selectors: List<Selector>,
        context: EngineExecutionContext
    ): Map<Selector, Result<Any?>> {
        // Only handle single selector case because this is an unbatched resolver
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got ${selectors.size}" }
        val selector = selectors.first()
        val result = resultOfSuspend {
            resolve(selector, context)
        }
        return mapOf(selector to result)
    }

    private suspend fun resolve(
        selector: Selector,
        context: EngineExecutionContext,
    ): Any? {
        val ctx = resolverContextFactory(
            engineExecutionContext = context,
            requestContext = context.requestContext, // TODO - get rid of this argument
            engineSelections = selector.selections,
            rawArguments = selector.arguments,
            syncObjectValueGetter = selector.syncObjectValueGetter,
            syncQueryValueGetter = selector.syncQueryValueGetter,
        )
        val resolver = mkResolver()
        val result: Any? = handleTenantErrorsSuspend(resolverName) {
            resolver.invokeFieldResolver(ctx)
        }
        return unwrapFieldResolverResult(result, context.globalIDCodec)
    }

    private fun mkResolver(): BaseUnbatchedFieldResolver = resolver.get()

    companion object {
        internal fun unwrapFieldResolverResult(
            result: Any?,
            globalIDCodec: GlobalIDCodec
        ): Any? {
            return when (result) {
                is ObjectBase -> result.__engineObject
                is List<*> -> result.map { unwrapFieldResolverResult(it, globalIDCodec) }
                is GlobalID<*> -> globalIDCodec.serialize(result.type.name, result.internalID)
                is Enum<*> -> result.name
                else -> result
            }
        }
    }
}
