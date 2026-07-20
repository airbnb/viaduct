package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.common.ExternalDataClient
import com.example.starwars.filmography.resolverbases.FilmResolvers
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import jakarta.inject.Named
import viaduct.api.resolver.Resolver

/**
 * Demonstrates per-tenant dependency injection: this resolver requests an [ExternalDataClient]
 * qualified `@Named("filmography")`, which Micronaut resolves to `FilmArchiveClient` — a
 * different bean than the one the universe tenant receives for the same requested type.
 */
@Resolver("id")
@Prototype
class FilmDataSourceResolver
    @Inject
    constructor(
        @Named("filmography") private val externalDataClient: ExternalDataClient,
    ) : FilmResolvers.DataSource() {
        override suspend fun resolve(ctx: Context): String? {
            val filmId = ctx.getObjectValue().getId().internalID
            return externalDataClient.fetchData(filmId)
        }
    }
