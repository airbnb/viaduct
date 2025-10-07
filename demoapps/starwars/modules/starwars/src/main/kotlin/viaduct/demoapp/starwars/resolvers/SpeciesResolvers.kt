package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver
import viaduct.api.grts.Character
import viaduct.api.grts.Planet
import viaduct.demoapp.starwars.builders.CharacterBuilder
import viaduct.demoapp.starwars.builders.PlanetBuilder
import viaduct.demoapp.starwars.data.StarWarsData
import viaduct.demoapp.starwars.resolverbases.SpeciesResolvers

@Resolver("id")
class SpeciesHomeworldResolver : SpeciesResolvers.Homeworld() {
    override suspend fun resolve(ctx: Context): Planet? {
        val species = ctx.objectValue
        val homeWorldId = StarWarsData.species.first { it.id == species.getId().internalID }.homeworldId
        val planetData = StarWarsData.planets.find { it.id == homeWorldId } ?: return null
        return PlanetBuilder(ctx).build(planetData)
    }
}

@Resolver("id")
class SpeciesPeopleResolver : SpeciesResolvers.People() {
    override suspend fun resolve(ctx: Context): List<Character?>? {
        val speciesId = ctx.objectValue.getId().internalID
        val people = StarWarsData.characters.filter { it.speciesId == speciesId }
        return people.map(CharacterBuilder(ctx)::build)
    }
}

@Resolver("id")
class SpeciesCulturalNotesResolver : SpeciesResolvers.CulturalNotes() {
    override suspend fun resolve(ctx: Context): String? {
        val speciesGrt = ctx.objectValue
        val speciesId = speciesGrt.getId().internalID
        val species = StarWarsData.species.find { it.id == speciesId }
        return species?.extrasData?.culturalNotes
    }
}

@Resolver("id")
class SpeciesRarityLevelResolver : SpeciesResolvers.RarityLevel() {
    override suspend fun resolve(ctx: Context): String? {
        val speciesGrt = ctx.objectValue
        val speciesId = speciesGrt.getId().internalID
        val species = StarWarsData.species.find { it.id == speciesId }
        return species?.extrasData?.rarityLevel
    }
}

@Resolver("id")
class SpeciesSpecialAbilitiesResolver : SpeciesResolvers.SpecialAbilities() {
    override suspend fun resolve(ctx: Context): List<String?>? {
        val speciesGrt = ctx.objectValue
        val speciesId = speciesGrt.getId().internalID
        val species = StarWarsData.species.find { it.id == speciesId }
        return species?.extrasData?.specialAbilities
    }
}

@Resolver("id")
class SpeciesTechnologicalLevelResolver : SpeciesResolvers.TechnologicalLevel() {
    override suspend fun resolve(ctx: Context): String? {
        val speciesGrt = ctx.objectValue
        val speciesId = speciesGrt.getId().internalID
        val species = StarWarsData.species.find { it.id == speciesId }
        return species?.extrasData?.technologicalLevel
    }
}
