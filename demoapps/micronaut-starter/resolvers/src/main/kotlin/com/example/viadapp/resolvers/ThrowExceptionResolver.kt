package com.example.viadapp.resolvers

import com.example.viadapp.resolvers.resolverbases.QueryResolvers
import viaduct.api.Resolver

@Resolver
class ThrowExceptionResolver : QueryResolvers.ThrowException() {
    override suspend fun resolve(ctx: Context): String {
        error("This is a resolver error")
    }
}
