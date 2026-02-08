package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData
import viaduct.tenant.runtime.toObjectGRT

/**
 * Base implementation of [BaseFieldExecutionContext] providing common functionality
 * for field and mutation resolvers.
 *
 * This sealed class handles:
 * - Access to query value (both lazy via [queryValue] and synchronous via [getQueryValue])
 * - Argument access
 * - Selection set access
 * - Request context access
 *
 * The [getQueryValue] method returns a synchronously-accessible version of the query value
 * where all selections have been eagerly resolved. This is useful when you need to ensure
 * all query data is available before proceeding, or when passing data to non-suspending code.
 *
 * @param syncQueryValueGetter A suspending function that returns the synchronous query value,
 *        or null if no query selections were declared by the resolver
 */
sealed class BaseFieldExecutionContextImpl<Q : Query>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selections: SelectionSet<CompositeOutput>,
    override val requestContext: Any?,
    override val arguments: Arguments,
    override val queryValue: Q,
    private val syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val queryCls: KClass<Q>,
) : BaseFieldExecutionContext<Q, Arguments, CompositeOutput>,
    ResolverExecutionContextImpl<Q>(baseData, engineExecutionContextWrapper) {
    override fun selections() = selections

    override suspend fun getQueryValue(): Q {
        val resolvedSyncQueryValue = syncQueryValueGetter?.invoke()
            ?: throw IllegalStateException(
                "Sync query value is not available. " +
                    "This may indicate an internal error in Viaduct."
            )
        return resolvedSyncQueryValue.toObjectGRT(this, queryCls)
    }
}
