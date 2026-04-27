package viaduct.tenant.runtime.execution

import javax.inject.Provider
import kotlin.reflect.KFunction
import viaduct.api.FieldValue
import viaduct.api.ResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.apiannotations.InTenantCode
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldResolverExecutor.Selector
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsResultSuspend
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory

/**
 * Executes a tenant-written field resolver's batchResolve function.
 *
 * @param resolverId: Uniquely identifies a resolver function, e.g. "User.fullName" identifies
 * the field resolver for the "fullName" field on the "User" type. This is used for observability.
 */
class FieldBatchResolverExecutorImpl(
    override val objectSelectionSet: RequiredSelectionSet?,
    override val querySelectionSet: RequiredSelectionSet?,
    override val isSelective: Boolean,
    internal val resolver: Provider<out @JvmSuppressWildcards ResolverBase<*>>, // internal for testing
    private val batchResolveFn: KFunction<*>,
    override val resolverId: String,
    private val reflectionLoader: ReflectionLoader,
    private val resolverContextFactory: FieldExecutionContextFactory,
    private val resolverName: String,
    private val tenantMetadata: TenantModuleMetadata? = null,
) : FieldResolverExecutor {
    override val metadata = ResolverMetadata.forModern(resolverName, ResolverType.FIELD, tenantMetadata)

    override val isBatching = true

    override suspend fun batchResolve(
        selectors: List<Selector>,
        context: EngineExecutionContext
    ): Map<Selector, Result<Any?>> {
        val contexts = selectors.map { key ->
            resolverContextFactory(
                engineExecutionContext = context,
                requestContext = context.requestContext, // TODO - get rid of this argument
                engineSelections = key.selections,
                rawArguments = key.arguments,
                rawObjectValue = key.objectValue,
                rawQueryValue = key.queryValue,
                syncObjectValueGetter = key.syncObjectValueGetter,
                syncQueryValueGetter = key.syncQueryValueGetter,
            )
        }
        val resolver = resolver.get()
        val results: Any? = handleTenantErrorsSuspend(resolverName) {
            callResolver(batchResolveFn, resolver, contexts)
        }
        if (results !is List<*>) {
            throw TenantUsageException("Unexpected return value from batchResolve function for field $resolverId: $results")
        }
        if (selectors.size != results.size) {
            throw TenantUsageException(
                "The batchResolve function in the field resolver for $resolverId was given a batch of size ${selectors.size} but returned ${results.size} elements"
            )
        }
        // If a Result is exceptional, its exception must already be one of the executor-allowed types.
        return selectors.zip(results.map { unwrap(it, context.globalIDCodec) }).toMap()
    }

    private suspend fun unwrap(
        fieldValue: Any?,
        globalIDCodec: GlobalIDCodec
    ): Result<Any?> {
        if (fieldValue !is FieldValue<*>) {
            throw IllegalStateException("Unexpected result type that is not a FieldValue: $fieldValue")
        }

        return handleTenantErrorsResultSuspend(resolverId) {
            unwrapFieldValue(fieldValue, globalIDCodec)
        }
    }

    @InTenantCode
    private fun unwrapFieldValue(
        fieldValue: FieldValue<*>,
        globalIDCodec: GlobalIDCodec
    ): Any? = FieldUnbatchedResolverExecutorImpl.unwrapFieldResolverResult(fieldValue.get(), globalIDCodec)
}
