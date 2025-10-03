package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.api.grts.Planet
import viaduct.api.grts.Species
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.PlanetBuilder
import viaduct.demoapp.starwars.builders.SpeciesBuilder
import viaduct.demoapp.starwars.data.StarWarsData

@Resolver("id")
class FilmCharactersResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.Characters() {
    override suspend fun resolve(ctx: Context): List<Character?>? {
        val filmId = ctx.objectValue.getId().internalID
        val characterIds = StarWarsData.filmCharacterRelations[filmId] ?: emptyList()
        val characters = StarWarsData.characters.filter { it.id in characterIds.toSet() }
        return characters.map(CharacterBuilder(ctx)::build)
    }
}

@Resolver("id")
class FilmPlanetsResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.Planets() {
    override suspend fun resolve(ctx: Context): List<Planet?>? {
        val filmId = ctx.objectValue.getId().internalID
        val characterIds = StarWarsData.filmCharacterRelations[filmId] ?: emptyList()
        val homeworldIds = StarWarsData.characters
            .filter { it.id in characterIds.toSet() }
            .mapNotNull { it.homeworldId }
            .toSet()
        val planets = StarWarsData.planets.filter { it.id in homeworldIds }
        return planets.map(PlanetBuilder(ctx)::build)
    }
}

@Resolver("id")
class FilmSpeciesResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.Species() {
    override suspend fun resolve(ctx: Context): List<Species?>? {
        val filmId = ctx.objectValue.getId().internalID
        val characterIds = StarWarsData.filmCharacterRelations[filmId] ?: emptyList()
        val speciesIds = StarWarsData.characters
            .filter { it.id in characterIds.toSet() }
            .mapNotNull { it.speciesId }
            .toSet()
        val species = StarWarsData.species.filter { it.id in speciesIds }
        return species.map(SpeciesBuilder(ctx)::build)
    }
}
