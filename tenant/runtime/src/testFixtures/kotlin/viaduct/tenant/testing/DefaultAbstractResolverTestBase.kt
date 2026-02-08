package viaduct.tenant.testing

import viaduct.api.context.ExecutionContext
import viaduct.api.internal.select.SelectionSetFactory

/**
 * Default implementation of [ResolverTestBase] with pre-configured test dependencies.
 *
 * ## Deprecation Notice
 *
 * **This class is deprecated.** Please migrate to the new type-safe testing API in
 * `viaduct.api.testing` package. The new API uses a factory pattern instead of inheritance,
 * provides explicit type parameters, and has zero runtime dependencies.
 *
 * See [ResolverTestBase] KDoc for detailed migration guide and examples.
 *
 * @see viaduct.api.testing.FieldResolverTester
 * @see viaduct.api.testing.MutationResolverTester
 * @see viaduct.api.testing.NodeResolverTester
 * @see ResolverTestBase for migration guide
 */
@Deprecated(
    message = "DefaultAbstractResolverTestBase is deprecated. Use the new type-safe testing API in viaduct.api.testing package. " +
        "See FieldResolverTester, MutationResolverTester, or NodeResolverTester for the new API.",
    level = DeprecationLevel.WARNING
)
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
