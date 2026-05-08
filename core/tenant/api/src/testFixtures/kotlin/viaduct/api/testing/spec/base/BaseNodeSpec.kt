package viaduct.api.testing.spec.base

import viaduct.api.context.NodeExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Base input carrier for node resolver inputs. Adds [selections] shared by
 * NodeResolverSpec and NodeBatchResolverSpec.
 * Mirrors [viaduct.api.context.NodeExecutionContext] in the hierarchy.
 */
@ExperimentalApi
abstract class BaseNodeSpec<T : NodeObject> : BaseResolverSpec() {
    var selections: SelectionSet<T>? = null

    @OptIn(InternalApi::class)
    protected fun getNodeContextKClass(resolverClass: Class<*>) = getResolverContextKClass<NodeExecutionContext<T>>(resolverClass)
}
