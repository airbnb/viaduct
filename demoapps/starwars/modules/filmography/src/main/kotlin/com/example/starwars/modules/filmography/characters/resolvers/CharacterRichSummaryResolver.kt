package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.resolver.Resolver

/**
 * **Multi-Source Batch Resolution** example for complex data combination.
 *
 * ## Features
 * - Combines data from multiple sources (characters, films, planets, species)
 * - Efficient fragment declaration for required fields
 *
 * ## Fragment Strategy
 * Includes fields accessed directly (`name`, `birthYear`) plus `id` for lookups and the homeworld's
 * `name`. The `name` and `birthYear` selections are shared with [CharacterDisplaySummaryResolver]
 * via the named fragment `CharacterIdentityFields` (see `CharacterIdentityFieldsFragment`), spread
 * here alongside the `id` this resolver needs for its batch lookups and the `homeworld { name }` it
 * needs for the summary. Other data is fetched through batch operations.
 */
@Resolver(
    objectValueFragment = "fragment _ on Character { id homeworld { name } ...CharacterIdentityFields }"
)
@Prototype
class CharacterRichSummaryResolver
    @Inject
    constructor(
        private val characterFilmsRepository: CharacterFilmsRepository
    ) : CharacterResolvers.RichSummary() {
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            val objectValues = contexts.map { it.getObjectValue() }
            val characterIds = objectValues.map { it.getIdOrThrow().internalID }

            val filmCounts = characterIds.associateWith { characterId ->
                characterFilmsRepository.findFilmsByCharacterId(characterId).size
            }

            return objectValues.mapIndexed { i, character ->
                val characterId = characterIds[i]

                val name = character.getNameOrThrow() ?: "Unknown"
                val birthYear = character.getBirthYearOrThrow() ?: "Unknown"
                val homeworldName = character.getHomeworld()?.getName() ?: "Unknown world"
                val filmCount = filmCounts[characterId] ?: 0

                val summary = "$name ($birthYear) from $homeworldName, appears in $filmCount films"
                FieldValue.ofValue(summary)
            }
        }
    }
