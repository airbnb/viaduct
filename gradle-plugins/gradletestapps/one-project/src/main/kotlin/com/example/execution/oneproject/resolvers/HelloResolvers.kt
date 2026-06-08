package com.example.execution.oneproject.resolvers

import com.example.execution.oneproject.resolvers.resolverbases.MutationResolvers
import com.example.execution.oneproject.resolvers.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "hello from one-project"
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "gradletestapps"
}

@Resolver
class EchoMutationResolver : MutationResolvers.Echo() {
    override suspend fun resolve(ctx: Context) = ctx.arguments.message
}
