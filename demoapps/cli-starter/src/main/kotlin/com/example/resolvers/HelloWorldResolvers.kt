// tag::resolvers-setup[20] Resolver logic setup.
package com.example.viadapp.resolvers

import com.example.viadapp.resolvers.resolverbases.MutationResolvers
import com.example.viadapp.resolvers.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

// tag::greeting-resolver[6] How to create a resolver
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
