package com.example.execution.multiproject.alpha

import com.example.execution.multiproject.alpha.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver(queryValueFragment = "fragment _ on Query { secretGreeting }")
class GreetingResolver : QueryResolvers.Greeting() {
    override suspend fun resolve(ctx: Context) = "hello from multi-project alpha"
}

@Resolver
class SecretGreetingResolver : QueryResolvers.SecretGreeting() {
    override suspend fun resolve(ctx: Context) = "private"
}
