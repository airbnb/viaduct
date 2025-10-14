package viaduct.demoapp.characters.viaduct.batchresolvers

import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Species
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.filmography.resolverbases.CharacterResolvers
import viaduct.demoapp.starwars.data.SpeciesBuilder
import viaduct.demoapp.starwars.data.StarWarsData

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
class SpeciesBatchResolver : CharacterResolvers.Species() {
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Species?>> {
        val characterIds = contexts.map { it.objectValue.getId().internalID }

        // Batch lookup characters and their species IDs
        val charactersById = CharacterRepository.findCharactersAsMap(characterIds)

        // Get all unique species IDs
        val speciesIds = charactersById.values
            .mapNotNull { it.speciesId }
            .toSet()

        // TODO: Replace with viaduct subquery when supported
        val speciesById = StarWarsData.species
            .filter { it.id in speciesIds }
            .associateBy { it.id }

        // Map each context to its corresponding Species in the given order
        return contexts.map { ctx ->
            // Related Character ID is stored in the ctx object value.
            val characterId = ctx.objectValue.getId().internalID

            // Find the character and its species
            val character = charactersById[characterId]
            val speciesData = character?.speciesId?.let { speciesById[it] }

            if (speciesData != null) {
                FieldValue.ofValue(SpeciesBuilder(ctx).build(speciesData))
            } else {
                FieldValue.ofValue(null)
            }
        }
    }
}
