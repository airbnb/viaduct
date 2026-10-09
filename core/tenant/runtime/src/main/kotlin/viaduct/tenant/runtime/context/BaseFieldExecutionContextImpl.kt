package viaduct.tenant.runtime.context

import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.Caller
import viaduct.api.internal.InternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi

/**
 * Base implementation of [BaseFieldExecutionContext] providing argument, selection set,
 * request context, and caller metadata access shared by field and mutation resolvers.
 */
sealed class BaseFieldExecutionContextImpl<Q : Query, A : Arguments, R : CompositeOutput>(
    baseData: InternalContext,
    engineExecutionContextWrapper: EngineExecutionContextWrapper,
    private val selectionSet: SelectionSet<R>,
    override val requestContext: Any?,
    override val arguments: A,
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
}
