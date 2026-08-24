package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.resolver.Resolver

/**
 * **Multi-Source Batch Resolution** example for complex data combination.
 *
 * ## Features
 * - Combines data from multiple sources (characters, films, planets, species)
 * - Uses deduplication to prevent duplicate lookups
 * - Efficient fragment declaration for required fields
 *
 * ## Fragment Strategy
 * Includes fields accessed directly (`name`, `birthYear`) plus `id` for lookups. The `name` and
 * `birthYear` selections are shared with [CharacterDisplaySummaryResolver] via the named fragment
 * `CharacterIdentityFields` (see `CharacterIdentityFieldsFragment`), spread here alongside the `id`
 * this resolver needs for its batch lookups. Other data is fetched through batch operations.
 */
@Resolver(objectValueFragment = "fragment _ on Character { id ...CharacterIdentityFields }")
@Prototype
class CharacterRichSummaryResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository,
        private val characterFilmsRepository: CharacterFilmsRepository
    ) : CharacterResolvers.RichSummary() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            val objectValues = contexts.map { it.getObjectValue() }
            val characterIds = objectValues.map { it.getIdOrThrow().internalID }

            val charactersById = characterIds.mapNotNull { characterRepository.findById(it) }.associateBy { it.id }

            val filmCounts = characterIds.associateWith { characterId ->
                characterFilmsRepository.findFilmsByCharacterId(characterId).size
            }

            // Batch lookup homeworld names
            val homeworldIds = charactersById.values.mapNotNull { it.homeworldId }.toSet()
            // TODO: Obtain homeworld from Viaduct

            return objectValues.mapIndexed { i, character ->
                val characterId = characterIds[i]
                val characterData = charactersById[characterId]

                val name = character.getNameOrThrow() ?: "Unknown"
                val birthYear = character.getBirthYearOrThrow() ?: "Unknown"
                val homeworldName = characterData?.homeworldId?.let { "TODO" } ?: "Unknown world"
                val filmCount = filmCounts[characterId] ?: 0

                val summary = "$name ($birthYear) from $homeworldName, appears in $filmCount films"
                FieldValue.ofValue(summary)
            }
        }
    }
