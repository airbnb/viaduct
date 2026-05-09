package com.example.viadapp

import com.example.viadapp.resolverbases.QueryResolvers
import viaduct.api.resolver.Resolver

@Resolver
class ThrowExceptionResolver : QueryResolvers.ThrowException() {
    override suspend fun resolve(ctx: Context): String {
        error("This is a resolver error")
    }
}
