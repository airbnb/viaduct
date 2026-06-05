package com.example.viadapp.resolvers

import com.example.viadapp.resolvers.resolverbases.MutationResolvers
import com.example.viadapp.resolvers.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "Hello, World!"
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "Brian Kernighan"
}

@Resolver
class GreetResolver : QueryResolvers.Greet() {
    override suspend fun resolve(ctx: Context) = "Hello, ${ctx.arguments.name}!"
}

@Resolver
class EchoMutationResolver : MutationResolvers.Echo() {
    override suspend fun resolve(ctx: Context) = ctx.arguments.message
}
