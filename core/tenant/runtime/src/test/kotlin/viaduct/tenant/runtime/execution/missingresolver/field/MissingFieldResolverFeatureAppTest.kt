@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.missingresolver.field

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.missingresolver.field.resolverbases.QueryResolvers

/**
 * Tests that FeatureAppTestBase validates resolver completeness at build time,
 * producing a clear error message when a @resolver-declared field
 * is missing its @Resolver implementation class.
 */
class MissingFieldResolverFeatureAppTest : MissingFieldResolverContractTest() {
    // Only implement one of the two resolvers — "forgotten" is intentionally missing
    @Resolver
    class ImplementedResolver : QueryResolvers.Implemented() {
        override suspend fun resolve(ctx: Context): String = "present"
    }
}
