package com.example.viadapp.resolvers

import com.example.viadapp.resolvers.resolverbases.QueryResolvers
import com.example.viadapp.resolvers.service.AsciiArtService
import viaduct.api.Resolver

/**
 * Resolver for the asciiArt query.
 *
 * Demonstrates dependency injection with Koin using constructor injection.
 * The [AsciiArtService] is provided by Koin via the [KoinTenantCodeInjector].
 */
@Resolver
class AsciiArtResolver(
    private val asciiArtService: AsciiArtService
) : QueryResolvers.AsciiArt() {

    override suspend fun resolve(ctx: Context): String {
        return asciiArtService.getViaductBanner()
    }
}
