package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.api.grts.Film
import viaduct.api.grts.Planet
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle
import viaduct.demoapp.starwars.NodeResolvers
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.FilmBuilder
import viaduct.demoapp.starwars.builders.PlanetBuilder
import viaduct.demoapp.starwars.builders.SpeciesBuilder
import viaduct.demoapp.starwars.builders.VehicleBuilder
import viaduct.demoapp.starwars.data.StarWarsData

/**
 * Concrete Node resolver implementations
 */

@Resolver
class CharacterNodeResolver : NodeResolvers.Character() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Character {
        val stringId = ctx.id.internalID
        val character = StarWarsData.characters.find { it.id == stringId }
            ?: throw IllegalArgumentException("Character with ID $stringId not found")

        return CharacterBuilder(ctx).build(character)
    }
}

@Resolver
class FilmNodeResolver : NodeResolvers.Film() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Film {
        val filmId = ctx.id.internalID
        val film = StarWarsData.films.find { it.id == filmId }
            ?: throw IllegalArgumentException("Film with ID $filmId not found")

        return FilmBuilder(ctx).build(film)
    }
}

@Resolver
class PlanetNodeResolver : NodeResolvers.Planet() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Planet {
        val stringId = ctx.id.internalID
        val planet = StarWarsData.planets.find { it.id == stringId }
            ?: throw IllegalArgumentException("Planet with ID $stringId not found")

        return PlanetBuilder(ctx).build(planet)
    }
}

@Resolver
class SpeciesNodeResolver : NodeResolvers.Species() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Species {
        val stringId = ctx.id.internalID
        val species = StarWarsData.species.find { it.id == stringId }
            ?: throw IllegalArgumentException("Species with ID $stringId not found")

        return SpeciesBuilder(ctx).build(species)
    }
}

@Resolver
class VehicleNodeResolver : NodeResolvers.Vehicle() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Vehicle {
        val stringId = ctx.id.internalID
        val vehicle = StarWarsData.vehicles.find { it.id == stringId }
            ?: throw IllegalArgumentException("Vehicle with ID $stringId not found")

        return VehicleBuilder(ctx).build(vehicle)
    }
}
