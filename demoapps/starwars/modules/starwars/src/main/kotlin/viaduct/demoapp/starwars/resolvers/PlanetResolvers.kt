package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.api.grts.Film
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.FilmBuilder
import viaduct.demoapp.starwars.data.StarWarsData

@Resolver("id")
class PlanetResidentsResolver : viaduct.demoapp.starwars.resolverbases.PlanetResolvers.Residents() {
    override suspend fun resolve(ctx: Context): List<Character?>? {
        val planetId = ctx.objectValue.getId().internalID
        val residents = StarWarsData.characters.filter { it.homeworldId == planetId }
        return residents.map(CharacterBuilder(ctx)::build)
    }
}

@Resolver("id")
class PlanetFilmsResolver : viaduct.demoapp.starwars.resolverbases.PlanetResolvers.Films() {
    override suspend fun resolve(ctx: Context): List<Film?>? {
        val planetId = ctx.objectValue.getId().internalID

        val residentCharacterIds = StarWarsData.characters
            .filter { it.homeworldId == planetId }
            .map { it.id }
            .toSet()

        val filmIds = StarWarsData.characterFilmRelations
            .filter { (characterId, _) -> characterId in residentCharacterIds }
            .flatMap { it.value }
            .toSet()

        val films = StarWarsData.films.filter { it.id in filmIds }
        return films.map(FilmBuilder(ctx)::build)
    }
}
