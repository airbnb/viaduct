package viaduct.tenant.testing

import io.mockk.mockk
import viaduct.api.context.ExecutionContext
import viaduct.engine.api.FragmentLoader
import viaduct.tenant.runtime.context.ResolverExecutionContextImpl

abstract class DefaultAbstractResolverTestBase : ResolverTestBase {
    override fun getFragmentLoader(): FragmentLoader = mockk()

    override val selectionsLoaderFactory by lazy {
        mkSelectionsLoaderFactory()
    }

    /**
     * An ExecutionContext that can be used to construct a builder, e.g. Foo.Builder(context).
     * This cannot be passed as the `ctx` param to the `resolve` function of a resolver, since
     * that's a subclass unique to the resolver.
     **/
    override val context: ExecutionContext by lazy {
        ResolverExecutionContextImpl(
            mkInternalContext(),
            requestContext = null,
            queryLoader = mkQueryLoader(),
            selectionSetFactory = mkSelectionSetFactory(),
            nodeReferenceFactory = mkNodeReferenceFactory()
        )
    }
}
