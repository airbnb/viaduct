package viaduct.demoapp.starwars.resolvers.batch

import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Planet
import viaduct.demoapp.starwars.builders.PlanetBuilder
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolverbases.CharacterResolvers

/**
 * Demonstrates **Basic Batch Resolution** for solving the N+1 query problem.
 *
 * ## The N+1 Problem and Solution
 *
 * Without batch resolvers, this query creates N+1 database calls:
 * ```graphql
 * query {
 *   allCharacters(first: 3) {
 *     characters {
 *       name
 *       homeworld { name }
 *     }
 *   }
 * }
 * ```
 *
 * **Without batching:** 1 query for characters + 3 individual homeworld queries = 4 total
 * **With batching:** 1 query for characters + 1 batch homeworld query = 2 total
 *
 * ## How Batch Resolvers Work
 *
 * 1. **Fragment Declaration**: `objectValueFragment` specifies required fields
 * 2. **Request Collection**: Framework groups multiple field requests together
 * 3. **Batch Processing**: Single `batchResolve()` call handles all requests
 * 4. **Result Distribution**: Results returned in same order as input contexts
 *
 * ## Key Rules
 *
 * - Fragment must include ALL fields the resolver accesses
 * - Results must be in exact same order as input contexts
 * - Use `FieldValue.ofValue(null)` for null values
 *
 * ## Performance Impact
 * For 100 characters: 101 queries → 2 queries (50x improvement)
 */
@Resolver(
    objectValueFragment = "fragment _ on Character { id }"
)
class CharacterHomeworldBatchResolver : CharacterResolvers.Homeworld() {
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Planet?>> {
        // Extract all character IDs that need homeworld resolution
        val characterIds = contexts.map { ctx ->
            ctx.objectValue.getId().internalID
        }

        // Batch lookup: find characters and their homeworld IDs
        val charactersById = StarWarsData.characters
            .filter { it.id in characterIds }
            .associateBy { it.id }

        // Get all unique homeworld IDs
        val homeworldIds = charactersById.values
            .mapNotNull { it.homeworldId }
            .toSet()

        // Batch lookup planets
        val planetsById = StarWarsData.planets
            .filter { it.id in homeworldIds }
            .associateBy { it.id }

        // Return results in the same order as contexts
        return contexts.map { ctx ->
            val characterId = ctx.objectValue.getId().internalID
            val character = charactersById[characterId]
            val planetData = character?.homeworldId?.let { planetsById[it] }

            if (planetData != null) {
                val planet = PlanetBuilder(ctx).build(planetData)
                FieldValue.ofValue(planet)
            } else {
                FieldValue.ofValue(null)
            }
        }
    }
}
