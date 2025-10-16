package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import viaduct.api.FieldValue
import viaduct.api.Resolver

/**
 * Demonstrates **Reverse Relationship Batching** for one-to-many relationships.
 *
 * ## Reverse vs Forward Relationships
 * - Forward: Character → Homeworld (many-to-one)
 * - Reverse: Film → Characters (one-to-many)
 *
 * ## Optimization Strategy
 * 1. Collect all needed character IDs across all films
 * 2. Build character objects once using deduplication
 * 3. Distribute pre-built objects to each film
 *
 * This prevents rebuilding the same character objects for multiple films.
 */
@Resolver(objectValueFragment = "fragment _ on Film { id }")
class FilmMainCharactersResolver : FilmResolvers.MainCharacters() {
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<viaduct.api.grts.Character>>> {
        val filmIds = contexts.map { it.objectValue.getId().internalID }

        // Batch lookup film-character relationships
        val filmCharacterMap = filmIds.associateWith { FilmCharactersRepository.findCharactersByFilmId(it) }

        // Batch build all needed character objects
        val charactersById = filmCharacterMap.values.flatten().toSet().associateWith {
            CharacterRepository.findById(it)?.id ?: throw IllegalArgumentException("Character with ID $it not found")
        }

        // Returns the list of characters for each film context in the given order
        return contexts.map { ctx ->
            // Get character IDs for this film
            val filmId = ctx.objectValue.getId().internalID
            val characterIds = filmCharacterMap[filmId] ?: emptyList()

            // Map character IDs to pre-built Character objects, preserving order
            val characters = characterIds.mapNotNull { characterId ->
                val character = CharacterRepository.findById(characterId) ?: return@mapNotNull null
                charactersById[characterId]?.let { CharacterBuilder(ctx).build(character) }
            }

            FieldValue.ofValue(characters)
        }
    }
}
