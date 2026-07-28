package com.example.tenant.resolverbases

import viaduct.api.FieldValue
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.api.internal.InternalContext
import viaduct.api.NodeResolverBase
import viaduct.api.internal.NodeResolverFor
import viaduct.api.select.SelectionSet

@OptIn(InternalApi::class, ExperimentalApi::class)
object NodeResolvers {
    @NodeResolverFor(typeName = "User", isSelective = false, isBatching = false)
    abstract class User : viaduct.api.ResolverBase<viaduct.api.grts.User>, NodeResolverBase<viaduct.api.grts.User>, viaduct.api.internal.BaseUnbatchedNodeResolver {
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.User

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeNodeResolver(
            context: viaduct.api.context.NodeExecutionContext<*>
        ): Any? = resolve(
            Context(context as viaduct.api.context.NodeExecutionContext<viaduct.api.grts.User>)
        )
        class Context(
            private val inner: viaduct.api.context.NodeExecutionContext<viaduct.api.grts.User>
        ) : viaduct.api.context.NodeExecutionContext<viaduct.api.grts.User> by inner, InternalContext by (inner as InternalContext) {
        }
    }
}