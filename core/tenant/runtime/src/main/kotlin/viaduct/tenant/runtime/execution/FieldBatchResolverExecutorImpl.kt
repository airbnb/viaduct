package viaduct.tenant.runtime.execution

import javax.inject.Provider
import viaduct.api.FieldValue
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldResolverExecutor.Selector
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FrameworkException
import viaduct.errors.PassthroughException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.errors.handleTenantErrorsSuspend
import viaduct.errors.resultOfSuspend
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
    internal val resolver: Provider<out @JvmSuppressWildcards BaseBatchedFieldResolver>, // internal for testing
    override val resolverId: String,
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
                syncObjectValueGetter = key.syncObjectValueGetter,
                syncQueryValueGetter = key.syncQueryValueGetter,
            )
        }
        val resolver = resolver.get()
        val results: Any? = handleTenantErrorsSuspend(resolverName) {
            resolver.invokeFieldBatchResolver(contexts)
        }
        if (results !is List<*>) {
            throw FrameworkException("Unexpected return value from batchResolve function for field $resolverId: $results")
        }
        if (selectors.size != results.size) {
            throw TenantResolverException(
                TenantUsageException(
                    "The batchResolve function in the field resolver for $resolverId was given a batch of size ${selectors.size} but returned ${results.size} elements"
                ),
                resolverId
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
            return Result.failure(
                TenantResolverException(
                    TenantUsageException("Unexpected result type that is not a FieldValue: $fieldValue"),
                    resolverId
                )
            )
        }

        // TODO: the pass through here for `ErroneousFieldException` is not our long-term
        // solution. Instead, we need a mechanism for passing field-level error information
        // from tenant exceptions into the final graphql field-error. See the
        // "GraphQL Error Message Shaping" discussion in
        // https://slate.sandcastle.musta.ch/I3TZD5c0dg; a solution for that would be a
        // better solution for `ErroneousFieldException`.
        return resultOfSuspend(
            mapException = { e ->
                if (e is PassthroughException || e is ErroneousFieldException) {
                    e
                } else {
                    TenantResolverException(e, resolverId)
                }
            }
        ) {
            unwrapFieldValue(fieldValue, globalIDCodec)
        }
    }

    @Attribution(AttributionContext.TENANT)
    private fun unwrapFieldValue(
        fieldValue: FieldValue<*>,
        globalIDCodec: GlobalIDCodec
    ): Any? = FieldUnbatchedResolverExecutorImpl.unwrapFieldResolverResult(fieldValue.get(), globalIDCodec)
}
