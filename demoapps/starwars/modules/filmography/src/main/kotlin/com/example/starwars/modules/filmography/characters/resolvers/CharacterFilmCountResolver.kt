package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver

/**
 * **Simple Batch Aggregation** for counting operations example.
 *
 * ## When to Use
 * - Counting relationships (films per character, comments per post)
 * - Simple calculations (totals, averages)
 * - Operations that benefit from batching but don't require complex object building
 *
 * ## How batchResolve Works
 *
 * The `batchResolve()` method is called by the framework when multiple field requests
 * are collected for the same resolver. Instead of calling `resolve()` individually
 * for each character, the framework groups them together.
 *
 * ### What `contexts` Contains
 * Each `Context` in the list represents one field resolution request:
 * ```kotlin
 * // For query: allCharacters { filmCount }
 * contexts = [
 *     Context(Character(id="1")),  // Luke's filmCount request
 *     Context(Character(id="2")),  // Leia's filmCount request
 *     Context(Character(id="3"))   // Han's filmCount request
 * ]
 * ```
 *
 * Each context provides:
 * - `ctx.objectValue`: The Character object this field belongs to
 * - `ctx.arguments`: Any arguments passed to the filmCount field
 * - Framework data for building results
 *
 * ## Efficiency
 * Instead of N individual count operations, performs one batch lookup and maps results.
 */
// tag::film_count_batch_resolver[20] FilmCountBatchResolver
@Resolver(objectValueFragment = "fragment _ on Character { id }")
class CharacterFilmCountResolver
    @Inject
    constructor(
        val characterFilmsRepository: CharacterFilmsRepository
    ) : CharacterResolvers.FilmCount() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Int>> {
            // Extract all unique character IDs from the contexts
            val characterIds = contexts.map { it.objectValue.getId().internalID }.toSet()

            // Perform a single batch query to get film counts for all characters
            // We only compute one time for each character, despite multiple requests
            val filmCounts = characterIds.associateWith { characterId ->
                characterFilmsRepository.findFilmsByCharacterId(characterId).size
            }

            // For each context gets the character ID and map to the precomputed film count
            // and return the results in the same order as contexts
            return contexts.map { ctx ->
                val characterId = ctx.objectValue.getId().internalID

                FieldValue.ofValue(filmCounts[characterId] ?: 0)
            }
        }
    }
