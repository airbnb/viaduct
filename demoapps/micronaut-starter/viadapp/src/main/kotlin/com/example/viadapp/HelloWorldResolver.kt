package com.example.viadapp

import com.example.viadapp.resolverbases.QueryResolvers
import viaduct.api.Resolver

@Resolver
class HelloWorldResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context): String {
        return "Hello, World!"
    }
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context): String {
        return "Brian Kernighan"
    }
}
