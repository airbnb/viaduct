package com.example.starwars.modules.universe.planets.resolvers

import com.example.starwars.common.ExternalDataClient
import com.example.starwars.universe.resolverbases.PlanetResolvers
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import jakarta.inject.Named
import viaduct.api.resolver.Resolver

/**
 * Demonstrates per-tenant dependency injection: this resolver requests an [ExternalDataClient]
 * qualified `@Named("universe")`, which Micronaut resolves to `UniverseCatalogClient` — a
 * different bean than the one the filmography tenant receives for the same requested type.
 */
@Resolver("id")
@Prototype
class PlanetDataSourceResolver
    @Inject
    constructor(
        @Named("universe") private val externalDataClient: ExternalDataClient,
    ) : PlanetResolvers.DataSource() {
        override suspend fun resolve(ctx: Context): String? {
            val planetId = ctx.getObjectValue().getIdOrThrow().internalID
            return externalDataClient.fetchData(planetId)
        }
    }
