package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.Film
import viaduct.api.grts.Planet
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle
import viaduct.demoapp.starwars.Constants.DEFAULT_PAGE_SIZE
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.FilmBuilder
import viaduct.demoapp.starwars.builders.PlanetBuilder
import viaduct.demoapp.starwars.builders.SpeciesBuilder
import viaduct.demoapp.starwars.builders.VehicleBuilder
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolverbases.QueryResolvers

/**
 * Query resolvers for the Star Wars GraphQL API.
 *
 * The Query type demonstrates the @scope directive which restricts schema access
 * to specific tenants or contexts. All resolvers here are scoped to "starwars".
 */

/**
 * @resolver directive: Custom field resolution for Query.searchCharacter
 * @oneOf directive: The search input uses @oneOf directive, ensuring exactly one
 *                  search criteria is provided. This demonstrates input validation
 *                  where only one of byName, byId, or byBirthYear can be specified.
 */
@Resolver
class SearchCharacterResolver : QueryResolvers.SearchCharacter() {
    override suspend fun resolve(ctx: Context): viaduct.api.grts.Character? {
        val search = ctx.arguments.search
        val byName = search.byName
        val byId = search.byId
        val byBirthYear = search.byBirthYear

        val character = when {
            byName != null -> StarWarsData.characters.find { it.name.contains(byName, ignoreCase = true) }
            byId != null -> StarWarsData.characters.find { it.id == byId.internalID }
            byBirthYear != null -> StarWarsData.characters.find { it.birthYear == byBirthYear }
            else -> null
        }

        return if (character != null) {
            CharacterBuilder(ctx).build(character)
        } else {
            null
        }
    }
}

/**
 * @resolver directive: Custom field resolution for Query.allCharacters
 * @backingData directive: Uses "starwars.query.AllCharacters" backing data class for pagination
 * @scope directive: This query is scoped to ["starwars"] tenant only
 */
@Resolver
class AllCharactersResolver : QueryResolvers.AllCharacters() {
    override suspend fun resolve(ctx: Context): List<viaduct.api.grts.Character?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val characters = StarWarsData.characters.take(limit)

        // Convert StarWarsData.Character objects to Character objects
        return characters.map { character ->
            CharacterBuilder(ctx).build(character)
        }
    }
}

/**
 * @resolver directive: Custom field resolution for Query.allFilms
 * @backingData directive: Uses "starwars.query.AllFilms" backing data class for pagination
 * @scope directive: This query is scoped to ["starwars"] tenant only
 */
@Resolver
class AllFilmsResolver : QueryResolvers.AllFilms() {
    override suspend fun resolve(ctx: Context): List<viaduct.api.grts.Film?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val films = StarWarsData.films.take(limit)

        // Convert StarWarsData.Film objects to Film objects
        return films.map(FilmBuilder(ctx)::build)
    }
}

@Resolver
class AllPlanetsResolver : QueryResolvers.AllPlanets() {
    override suspend fun resolve(ctx: Context): List<viaduct.api.grts.Planet?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val planets = StarWarsData.planets.take(limit)
        return planets.map(PlanetBuilder(ctx)::build)
    }
}

@Resolver
class AllSpeciesResolver : QueryResolvers.AllSpecies() {
    override suspend fun resolve(ctx: Context): List<viaduct.api.grts.Species?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val species = StarWarsData.species.take(limit)
        return species.map(SpeciesBuilder(ctx)::build)
    }
}

@Resolver
class AllVehiclesResolver : QueryResolvers.AllVehicles() {
    override suspend fun resolve(ctx: Context): List<viaduct.api.grts.Vehicle?>? {
        val limit = ctx.arguments.limit ?: DEFAULT_PAGE_SIZE
        val vehicles = StarWarsData.vehicles.take(limit)
        return vehicles.map(VehicleBuilder(ctx)::build)
    }
}
