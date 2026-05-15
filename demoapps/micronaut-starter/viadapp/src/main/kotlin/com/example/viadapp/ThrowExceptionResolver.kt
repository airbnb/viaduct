package com.example.viadapp

import com.example.viadapp.resolverbases.QueryResolvers
import jakarta.inject.Singleton
import viaduct.api.resolver.Resolver

@Resolver
@Singleton
class ThrowExceptionResolver : QueryResolvers.ThrowException() {
    override suspend fun resolve(ctx: Context): Nothing = error("This is a resolver error")
}
