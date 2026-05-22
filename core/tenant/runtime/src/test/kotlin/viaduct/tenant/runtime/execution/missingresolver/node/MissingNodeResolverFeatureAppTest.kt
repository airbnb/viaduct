@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.missingresolver.node

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.missingresolver.node.resolverbases.QueryResolvers

/**
 * Tests that MissingNodeResolverContractTest validates resolver completeness at build time,
 * producing a clear error message when a @resolver-declared node type
 * is missing its @Resolver implementation class.
 */
class MissingNodeResolverFeatureAppTest : MissingNodeResolverContractTest() {
    // Provide the field resolver but NOT the node resolver
    @Resolver
    class WidgetQueryResolver : QueryResolvers.Widget() {
        override suspend fun resolve(ctx: Context): Widget {
            val globalId = ctx.globalIDFor(Widget.Reflection, ctx.arguments.id)
            return ctx.nodeRef(globalId)
        }
    }
}
