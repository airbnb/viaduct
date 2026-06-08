package com.example.execution.twoproject.resolvers

import com.example.execution.twoproject.resolvers.resolverbases.MutationResolvers
import com.example.execution.twoproject.resolvers.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "hello from two-project"
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "gradletestapps"
}

@Resolver
class EchoMutationResolver : MutationResolvers.Echo() {
    override suspend fun resolve(ctx: Context) = ctx.arguments.message
}
