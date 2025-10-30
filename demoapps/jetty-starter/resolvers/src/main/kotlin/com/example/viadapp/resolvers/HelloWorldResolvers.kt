package com.example.viadapp.resolvers

import com.example.viadapp.resolvers.resolverbases.QueryResolvers
import viaduct.api.Resolver

@Resolver
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context): String {
        return "Hello from Jetty + Viaduct!"
    }
}

@Resolver
class AuthorResolver : QueryResolvers.Author() {
    override suspend fun resolve(ctx: Context): String {
        return "Viaduct GraphQL with Jetty"
    }
}

@Resolver
class ThrowExceptionResolver : QueryResolvers.ThrowException() {
    override suspend fun resolve(ctx: Context): String {
        throw IllegalStateException("This is a resolver error")
    }
}
