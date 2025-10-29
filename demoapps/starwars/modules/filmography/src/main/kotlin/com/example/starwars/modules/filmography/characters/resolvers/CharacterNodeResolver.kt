package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.NodeResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Character

/**
 * Node resolver for the Character type in the Star Wars GraphQL API.
 *
 * This resolver handles fetching a Character by its global ID.
 */
// tag::node_resolver_example[17] Example of a node resolver
@Resolver
class CharacterNodeResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : NodeResolvers.Character() {
        // Node resolvers can also be batched to optimize multiple requests
        // tag::node_batch_resolver_example[21] Example of a node resolver
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Character>> {
            // Extract all unique character IDs from the contexts
            val characterIds = contexts.map { it.id.internalID }

            // Perform a single batch query to get film counts for all characters
            // We only compute one time for each character, despite multiple requests
            val characters = characterIds.mapNotNull {
                characterRepository.findById(it)
            }

            // For each context gets the character ID and map to the viaduct object
            return contexts.map { ctx ->
                val characterId = ctx.id.internalID
                characters.firstOrNull { it.id == characterId }?.let {
                    FieldValue.ofValue(
                        CharacterBuilder(ctx).build(it)
                    )
                } ?: FieldValue.ofError(IllegalArgumentException("Character not found: $characterId"))
            }
        }
    }
