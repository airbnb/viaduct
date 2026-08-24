package viaduct.tenant.runtime.context

import kotlin.reflect.KClass
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.Caller
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.EngineObjectData
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.tenant.runtime.toObjectGRT

/**
 * Base implementation of [BaseFieldExecutionContext] providing common functionality
 * for field and mutation resolvers.
 *
 * This sealed class handles:
 * - Access to query value via [getQueryValue]
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
sealed class BaseFieldExecutionContextImpl<Q : Query, A : Arguments, R : CompositeOutput>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selectionSet: SelectionSet<R>,
    override val requestContext: Any?,
    override val arguments: A,
    private val syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)?,
    private val queryCls: KClass<Q>,
    private val ownedSelectionSet: Lazy<SelectionSet<R>>,
) : BaseFieldExecutionContext<Q, A, R>,
    ResolverExecutionContextImpl<Q>(baseData, engineExecutionContextWrapper) {
    @ExperimentalApi
    override val caller: Caller? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        engineExecutionContextWrapper.engineExecutionContext.fieldScope.caller?.let {
            Caller(
                tenantName = it.tenantName,
                typeName = it.typeName,
                fieldName = it.fieldName,
            )
        }
    }

    protected fun selectionSet(): SelectionSet<R> = selectionSet

    protected fun ownedSelectionSet(): SelectionSet<R> = ownedSelectionSet.value

    override suspend fun getQueryValue(): Q =
        handleFrameworkErrorsSuspend("getQueryValue") {
            val resolvedSyncQueryValue = syncQueryValueGetter?.invoke()
                ?: throw FrameworkException(
                    "Sync query value is not available. " +
                        "This may indicate an internal error in Viaduct."
                )
            resolvedSyncQueryValue.toObjectGRT(this, queryCls)
        }
}
