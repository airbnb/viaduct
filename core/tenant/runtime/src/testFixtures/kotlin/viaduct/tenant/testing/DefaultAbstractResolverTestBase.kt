@file:OptIn(VisibleForTest::class)

package viaduct.tenant.testing

import viaduct.api.context.ExecutionContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest

/**
 * Default implementation of [ResolverTestBase] with pre-configured test dependencies.
 */

@ExperimentalApi
@OptIn(InternalApi::class)
abstract class DefaultAbstractResolverTestBase : ResolverTestBase {
    override val ossSelectionSetFactory: SelectionSetFactory by lazy {
        mkSelectionSetFactory()
    }

    /**
     * An ExecutionContext that can be used to construct a builder, e.g. Foo.Builder(context).
     * This cannot be passed as the `ctx` param to the `resolve` function of a resolver, since
     * that's a subclass unique to the resolver.
     **/
    override val context: ExecutionContext by lazy {
        mkExecutionContext()
    }
}
