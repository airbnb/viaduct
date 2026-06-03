package com.example.viadapp

import com.example.viadapp.resolverbases.MutationResolvers
import com.example.viadapp.resolverbases.QueryResolvers
import jakarta.inject.Singleton
import viaduct.api.resolver.Resolver

@Resolver
@Singleton
class HelloWorldResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "Hello, World!"
}

@Resolver
@Singleton
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "Brian Kernighan"
}

@Resolver
@Singleton
class GreetResolver : QueryResolvers.Greet() {
    override suspend fun resolve(ctx: Context) = "Hello, ${ctx.arguments.name}!"
}

@Resolver
@Singleton
class EchoMutationResolver : MutationResolvers.Echo() {
    override suspend fun resolve(ctx: Context) = ctx.arguments.message
}
