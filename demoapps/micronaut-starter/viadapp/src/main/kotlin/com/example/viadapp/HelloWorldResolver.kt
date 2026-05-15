package com.example.viadapp

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
