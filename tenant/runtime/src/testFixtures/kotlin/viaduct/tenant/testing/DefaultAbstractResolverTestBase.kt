package viaduct.tenant.testing

import io.mockk.mockk
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.engine.api.FragmentLoader

abstract class DefaultAbstractResolverTestBase : ResolverTestBase {
    override fun getFragmentLoader(): FragmentLoader = mockk()

    override val selectionsLoaderFactory: SelectionsLoader.Factory by lazy {
        mkSelectionsLoaderFactory()
    }

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
