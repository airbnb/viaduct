package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.Resolver

/**
 * **Multi-Source Batch Resolution** example for complex data combination.
 *
 * ## Features
 * - Combines data from multiple sources (characters, films, planets, species)
 * - Uses deduplication to prevent duplicate lookups
 * - Efficient fragment declaration for required fields
 *
 * ## Fragment Strategy
 * Includes fields accessed directly (`name`, `birthYear`) plus `id` for lookups.
 * Other data fetched through batch operations.
 */
@Resolver(objectValueFragment = "fragment _ on Character { id name birthYear }")
class CharacterRichSummaryResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val characterFilmsRepository: CharacterFilmsRepository
    ) : CharacterResolvers.RichSummary() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            val characterIds = contexts.map { it.objectValue.getId().internalID }

            val charactersById = characterIds.mapNotNull { characterRepository.findById(it) }.associateBy { it.id }

            val filmCounts = characterIds.associateWith { characterId ->
                characterFilmsRepository.findFilmsByCharacterId(characterId).size
            }

            // Batch lookup homeworld names
            val homeworldIds = charactersById.values.mapNotNull { it.homeworldId }.toSet()
            // TODO: Obtain homeworld from Viaduct

            return contexts.map { ctx ->
                val character = ctx.objectValue
                val characterId = character.getId().internalID
                val characterData = charactersById[characterId]

                val name = character.getName() ?: "Unknown"
                val birthYear = character.getBirthYear() ?: "Unknown"
                val homeworldName = characterData?.homeworldId?.let { "TODO" } ?: "Unknown world"
                val filmCount = filmCounts[characterId] ?: 0

                val summary = "$name ($birthYear) from $homeworldName, appears in $filmCount films"
                FieldValue.ofValue(summary)
            }
        }
    }
