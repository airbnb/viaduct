package com.example.starwars.modules.universe.starships.queries

import com.example.starwars.modules.universe.starships.models.StarshipBuilder
import com.example.starwars.modules.universe.starships.models.StarshipsRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import viaduct.api.Resolver
import viaduct.api.grts.Starship

const val DEFAULT_PAGE_SIZE = 10

/**
 * Resolver for the `allStarships` query in the Star Wars GraphQL API.
 *
 * This resolver fetches a list of starships, limited by the provided argument or a default page size.
 */
@Resolver
class AllStarshipsQueryResolver : QueryResolvers.AllStarships() {
    override suspend fun resolve(ctx: Context): List<Starship?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val starships = StarshipsRepository.findAll().take(limit)
        return starships.map { starship -> StarshipBuilder(ctx).build(starship) }
    }
}
