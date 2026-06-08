package com.example.execution.multiproject.beta

import com.example.execution.multiproject.beta.resolverbases.MutationResolvers
import com.example.execution.multiproject.beta.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "hello from multi-project beta"
}

@Resolver
class EchoMutationResolver : MutationResolvers.Echo() {
    override suspend fun resolve(ctx: Context) = ctx.arguments.message
}
