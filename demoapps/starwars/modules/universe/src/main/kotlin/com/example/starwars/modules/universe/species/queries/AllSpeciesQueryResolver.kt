package com.example.starwars.modules.universe.species.queries

import com.example.starwars.modules.universe.species.models.SpeciesBuilder
import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.universe.resolverbases.QueryResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Species

const val DEFAULT_PAGE_SIZE = 10

/**
 * Resolver for the `allSpecies` query in the Star Wars GraphQL API.
 *
 * This resolver fetches a list of species, limited by the provided argument or a default page size.
 */
@Resolver
class AllSpeciesQueryResolver
    @Inject
    constructor(
        private val speciesRepository: SpeciesRepository
    ) : QueryResolvers.AllSpecies() {
        override suspend fun resolve(ctx: Context): List<Species?>? {
            val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
            val species = speciesRepository.findAll().take(limit)
            return species.map(SpeciesBuilder(ctx)::build)
        }
    }
