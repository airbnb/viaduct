package viaduct.demoapp.starwars.resolvers.batch

import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Species
import viaduct.demoapp.starwars.builders.SpeciesBuilder
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolverbases.CharacterResolvers

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
class CharacterSpeciesBatchResolver : CharacterResolvers.Species() {
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Species?>> {
        val characterIds = contexts.map { it.objectValue.getId().internalID }

        // Batch lookup characters and their species IDs
        val charactersById = StarWarsData.characters
            .filter { it.id in characterIds }
            .associateBy { it.id }

        // Get all unique species IDs
        val speciesIds = charactersById.values
            .mapNotNull { it.speciesId }
            .toSet()

        // Batch lookup species
        val speciesById = StarWarsData.species
            .filter { it.id in speciesIds }
            .associateBy { it.id }

        return contexts.map { ctx ->
            val characterId = ctx.objectValue.getId().internalID
            val character = charactersById[characterId]
            val speciesData = character?.speciesId?.let { speciesById[it] }

            if (speciesData != null) {
                val species = SpeciesBuilder(ctx).build(speciesData)
                FieldValue.ofValue(species)
            } else {
                FieldValue.ofValue(null)
            }
        }
    }
}
