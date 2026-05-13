package com.example.viadapp

import com.example.viadapp.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class HelloWorldResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "Hello, World!"
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context) = "Brian Kernighan"
}
