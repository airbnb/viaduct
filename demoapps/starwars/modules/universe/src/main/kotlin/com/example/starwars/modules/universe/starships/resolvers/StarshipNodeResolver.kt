package com.example.starwars.modules.universe.starships.resolvers

import com.example.starwars.modules.universe.starships.models.StarshipBuilder
import com.example.starwars.modules.universe.starships.models.StarshipsRepository
import com.example.starwars.universe.NodeResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Starship

/**
 * Node resolver for the Starship type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Starship by its global ID.
 */
@Resolver
class StarshipNodeResolver
    @Inject
    constructor(
        private val starshipsRepository: StarshipsRepository
    ) : NodeResolvers.Starship() {
        override suspend fun resolve(ctx: Context): Starship {
            val stringId = ctx.id.internalID
            val starship = starshipsRepository.findById(stringId)
                ?: throw IllegalArgumentException("Starship with ID $stringId not found")

            return StarshipBuilder(ctx).build(starship)
        }
    }
