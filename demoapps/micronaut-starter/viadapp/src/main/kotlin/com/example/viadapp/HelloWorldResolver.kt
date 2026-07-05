package com.example.viadapp

import com.example.viadapp.resolverbases.MutationResolvers
import com.example.viadapp.resolverbases.QueryResolvers
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import viaduct.api.resolver.Resolver

@Resolver
@Singleton
class HelloWorldResolver(
    @Value("\${greeting.message:Hello, World!}") private val message: String,
) : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = message
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
