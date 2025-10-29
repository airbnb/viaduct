package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.context.globalIDFor
import viaduct.api.grts.Species

/**
 * Demonstrates **Species Batch Resolution** with proper null handling.
 *
 * ## Similar Pattern to Homeworld Resolution
 * This follows the same batch pattern as homeworld resolution but for species relationships.
 * Shows consistent approach across different entity types.
 *
 * ## Key Features
 * - Deduplication of species lookups using `toSet()`
 * - Proper null handling with `FieldValue.ofValue(null)`
 * - Efficient batch processing for many-to-one relationships
 */
@Resolver(
    objectValueFragment = "fragment _ on Character { id }"
)
class CharacterSpeciesResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : CharacterResolvers.Species() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Species?>> {
            val characterIds = contexts.map { it.objectValue.getId().internalID }

            // Batch lookup characters and their species IDs
            val charactersById = characterRepository.findCharactersAsMap(characterIds)

            // Map each context to its corresponding Species in the given order
            return contexts.map { ctx ->
                // Related Character ID is stored in the ctx object value.
                val characterId = ctx.objectValue.getId().internalID

                // Find the character and its species
                val character = charactersById[characterId]

                val specie = character?.speciesId?.let {
                    ctx.nodeFor(ctx.globalIDFor<Species>(it))
                }

                if (specie != null) {
                    FieldValue.ofValue(specie)
                } else {
                    FieldValue.ofValue(null)
                }
            }
        }
    }
