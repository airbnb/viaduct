package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.AddCharacterToFilmPayload
import viaduct.api.grts.Character
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.FilmBuilder
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolverbases.MutationResolvers

@Resolver
class CreateCharacterResolvers : MutationResolvers.CreateCharacter() {
    override suspend fun resolve(ctx: Context): Character {
        val input = ctx.arguments.input
        val homeworldId = input.inputData["homeworldId"]?.toString()
        if (homeworldId == null || StarWarsData.planets.none { it.id == homeworldId }) {
            throw IllegalArgumentException("Planet with ID $homeworldId not found")
        }
        val speciesId = input.inputData["speciesId"]?.toString()
        if (speciesId == null || StarWarsData.species.none { it.id == speciesId }) {
            throw IllegalArgumentException("Species with ID $speciesId not found")
        }
        val character = StarWarsData.Character(
            id = "${StarWarsData.charactersIdSequence.andIncrement}",
            name = input.name,
            birthYear = input.birthYear,
            eyeColor = input.eyeColor,
            gender = input.gender,
            hairColor = input.hairColor,
            height = input.height,
            mass = input.mass?.toFloat(),
            homeworldId = homeworldId,
            speciesId = speciesId,
        )
        StarWarsData.characters.add(character)

        return CharacterBuilder(ctx).build(character)
    }
}

@Resolver
class UpdateCharacterNameResolvers : MutationResolvers.UpdateCharacterName() {
    override suspend fun resolve(ctx: Context): Character? {
        val id = ctx.arguments.id
        val name = ctx.arguments.name

        val character = StarWarsData.characters.find { it.id == id.internalID }
            ?: throw IllegalArgumentException("Character with ID ${id.internalID} not found")

        val updatedCharacter = character.copy(name = name)
        StarWarsData.characters.removeIf { it.id == id.internalID }
        StarWarsData.characters.add(updatedCharacter)

        return CharacterBuilder(ctx).build(updatedCharacter)
    }
}

@Resolver
class AddCharacterToFilmResolvers : MutationResolvers.AddCharacterToFilm() {
    override suspend fun resolve(ctx: Context): AddCharacterToFilmPayload? {
        val input = ctx.arguments.input
        val filmId = input.inputData["filmId"]?.toString()
        val characterId = input.inputData["characterId"]?.toString()
        val film = StarWarsData.films.find { it.id == filmId }
            ?: throw IllegalArgumentException("Film with ID $filmId not found")
        val character = StarWarsData.characters.find { it.id == characterId }
            ?: throw IllegalArgumentException("Character with ID $characterId not found")

        val characterFilmRelations = StarWarsData.characterFilmRelations[character.id]
        if (characterFilmRelations != null) {
            if (characterFilmRelations.contains(film.id)) {
                throw IllegalArgumentException("Character with ID ${character.id} is already in film with ID ${film.id}")
            } else {
                characterFilmRelations.add(film.id)
            }
        } else {
            StarWarsData.characterFilmRelations[character.id] = mutableListOf(film.id)
        }
        val filmCharacterRelations = StarWarsData.filmCharacterRelations[film.id]
        if (filmCharacterRelations != null) {
            if (filmCharacterRelations.contains(character.id)) {
                throw IllegalArgumentException("Film with ID ${film.id} already has character with ID ${character.id}")
            } else {
                filmCharacterRelations.add(character.id)
            }
        } else {
            StarWarsData.filmCharacterRelations[film.id] = mutableListOf(character.id)
        }

        val filmGrt = FilmBuilder(ctx).build(film)
        val characterGrt = CharacterBuilder(ctx).build(character)
        return AddCharacterToFilmPayload.Builder(ctx)
            .film(filmGrt)
            .character(characterGrt)
            .build()
    }
}

@Resolver
class DeleteCharacterResolvers : MutationResolvers.DeleteCharacter() {
    override suspend fun resolve(ctx: Context): Boolean? {
        val id = ctx.arguments.id
        StarWarsData.characters.find { it.id == id.internalID }
            ?: throw IllegalArgumentException("Character with ID ${id.internalID} not found")

        // Remove character from characters list
        StarWarsData.characters.removeIf { it.id == id.internalID }

        // Remove character from any films they were associated with
        val filmIds = StarWarsData.characterFilmRelations[id.internalID]
        if (filmIds != null) {
            for (filmId in filmIds) {
                StarWarsData.filmCharacterRelations[filmId]?.remove(id.internalID)
            }
            StarWarsData.characterFilmRelations.remove(id.internalID)
        }

        return true
    }
}
