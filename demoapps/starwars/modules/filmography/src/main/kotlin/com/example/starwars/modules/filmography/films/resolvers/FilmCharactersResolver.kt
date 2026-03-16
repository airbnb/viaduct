package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import com.example.starwars.modules.filmography.characters.models.CharacterBuilder
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.films.models.FilmCastData
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.grts.Character

/**
 * Example of a relationship field resolver in the Film type.
 *
 * Uses [FilmCastData] backing data resolved by [FilmCastDataResolver] so that
 * the `findCharactersByFilmId` call is shared with [FilmCharacterCountSummaryResolver].
 * When both `characters` and `characterCountSummary` are requested for the same film,
 * the repository is called once instead of twice.
 */
@Resolver(objectValueFragment = "fragment _ on Film { castData }")
class FilmCharactersResolver
    @Inject
    constructor(
        private val characterRepository: CharacterRepository
    ) : FilmResolvers.Characters() {
        override suspend fun resolve(ctx: Context): List<Character?>? {
            val castData = ctx.objectValue.get<FilmCastData>("castData", FilmCastData::class)
            return castData.characterIds.map { id ->
                val character = characterRepository.findById(id)
                    ?: throw IllegalArgumentException("Character with ID $id not found")
                CharacterBuilder(ctx).build(character)
            }
        }
    }
