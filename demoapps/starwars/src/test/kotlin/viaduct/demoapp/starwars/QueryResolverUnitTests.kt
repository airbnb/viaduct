@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.demoapp.starwars

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.CharacterSearchInput
import viaduct.api.grts.Query_AllCharacters_Arguments
import viaduct.api.grts.Query_AllFilms_Arguments
import viaduct.api.grts.Query_AllPlanets_Arguments
import viaduct.api.grts.Query_AllSpecies_Arguments
import viaduct.api.grts.Query_AllVehicles_Arguments
import viaduct.api.grts.Query_SearchCharacter_Arguments
import viaduct.demoapp.characters.models.repository.CharacterRepository
import viaduct.demoapp.characters.viaduct.queryresolvers.AllCharactersResolver
import viaduct.demoapp.characters.viaduct.queryresolvers.CharacterNodeResolver
import viaduct.demoapp.characters.viaduct.queryresolvers.SearchCharacterResolver
import viaduct.demoapp.films.models.repository.FilmsRepository
import viaduct.demoapp.films.viaduct.queryresolvers.AllFilmsResolver
import viaduct.demoapp.films.viaduct.queryresolvers.FilmNodeResolver
import viaduct.demoapp.universe.planets.viaduct.resolvers.AllPlanetsResolver
import viaduct.demoapp.universe.planets.viaduct.resolvers.PlanetNodeResolver
import viaduct.demoapp.universe.species.models.repository.SpeciesRepository
import viaduct.demoapp.universe.species.viaduct.queryresolvers.AllSpeciesResolver
import viaduct.demoapp.universe.species.viaduct.queryresolvers.SpeciesNodeResolver
import viaduct.demoapp.universe.vehicles.models.repository.VehiclesRepository
import viaduct.demoapp.universe.vehicles.viaduct.resolvers.AllVehiclesResolver
import viaduct.demoapp.universe.vehicles.viaduct.resolvers.VehicleNodeResolver
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

@OptIn(ExperimentalCoroutinesApi::class)
class QueryResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        SchemaFactory(DefaultCoroutineInterop)
            .fromResources("viaduct.demoapp.starwars", Regex(".*\\.graphqls"))

    @Test
    fun `search character by name returns a matching character`(): Unit =
        runBlocking {
            val reference = CharacterRepository.findAll().first()
            val resolver = SearchCharacterResolver()

            val args = Query_SearchCharacter_Arguments.Builder(context)
                .search(
                    CharacterSearchInput.Builder(context)
                        .byName(reference.name.substring(0, 2))
                        .build()
                )
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            result!!
            assertEquals(reference.name, result.getName())
            assertEquals(reference.birthYear, result.getBirthYear())
        }

    @Test
    fun `search character by id returns exact character`(): Unit =
        runBlocking {
            val reference = CharacterRepository.findAll().first()
            val resolver = SearchCharacterResolver()

            val gid = context.globalIDFor(Character.Reflection, reference.id)

            val args = Query_SearchCharacter_Arguments.Builder(context)
                .search(
                    CharacterSearchInput.Builder(context)
                        .byId(gid)
                        .build()
                )
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(reference.name, result!!.getName())
        }

    @Test
    fun `allCharacters respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 3
            val resolver = AllCharactersResolver()

            val args = Query_AllCharacters_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = CharacterRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
            assertEquals(ref.birthYear, first.getBirthYear())
        }

    @Test
    fun `allFilms respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 2
            val resolver = AllFilmsResolver()

            val args = Query_AllFilms_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = FilmsRepository.getAllFilms().first()
            val first = result.first()!!
            assertEquals(ref.title, first.getTitle())
            assertEquals(ref.episodeID, first.getEpisodeID())
        }

    @Test
    fun `allPlanets respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 4
            val resolver = AllPlanetsResolver()

            val args = Query_AllPlanets_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val first = result.first()!!
            assertEquals("Tatooine", first.getName())
        }

    @Test
    fun `allSpecies respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 1
            val resolver = AllSpeciesResolver()

            val args = Query_AllSpecies_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = SpeciesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
        }

    @Test
    fun `allVehicles respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 1
            val resolver = AllVehiclesResolver()

            val args = Query_AllVehicles_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = VehiclesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
            assertEquals(ref.model, first.getModel())
        }

    @Test
    fun `vehicle by id returns the correct Vehicle using node resolver`(): Unit =
        runBlocking {
            val ref = VehiclesRepository.findAll().first()
            val resolver = VehicleNodeResolver()

            // Create global ID for the vehicle
            val vehicleGlobalId = context.globalIDFor(viaduct.api.grts.Vehicle.Reflection, ref.id)

            // Use runNodeResolver to fetch vehicle
            val result = runNodeResolver(resolver, vehicleGlobalId)

            assertNotNull(result)
            assertEquals(ref.name, result.getName())
        }

    @Test
    fun `character by id returns the correct Character using node resolver`(): Unit =
        runBlocking {
            val ref = CharacterRepository.findAll().first()
            val resolver = CharacterNodeResolver()

            // Create global ID for the character
            val characterGlobalId = context.globalIDFor(Character.Reflection, ref.id)

            // Use runNodeResolver to fetch character
            val result = runNodeResolver(resolver, characterGlobalId)

            assertNotNull(result)
            assertEquals(ref.name, result.getName())
        }

    @Test
    fun `film by id returns the correct Film using node resolver`(): Unit =
        runBlocking {
            val ref = FilmsRepository.getAllFilms().first()
            val resolver = FilmNodeResolver()

            // Create global ID for the film
            val filmGlobalId = context.globalIDFor(viaduct.api.grts.Film.Reflection, ref.id)

            // Use runNodeResolver to fetch film
            val result = runNodeResolver(resolver, filmGlobalId)

            assertNotNull(result)
            assertEquals(ref.title, result.getTitle())
        }

    @Test
    fun `planet by id returns the correct Planet using node resolver`(): Unit =
        runBlocking {
            val resolver = PlanetNodeResolver()

            // Create global ID for the planet
            val planetGlobalId = context.globalIDFor(viaduct.api.grts.Planet.Reflection, "1")

            // Use runNodeResolver to fetch planet
            val result = runNodeResolver(resolver, planetGlobalId)

            assertNotNull(result)
            assertEquals("Tatooine", result.getName())
        }

    @Test
    fun `species by id returns the correct Species using node resolver`(): Unit =
        runBlocking {
            val ref = SpeciesRepository.findAll().first()
            val resolver = SpeciesNodeResolver()

            // Create global ID for the species
            val speciesGlobalId = context.globalIDFor(viaduct.api.grts.Species.Reflection, ref.id)

            // Use runNodeResolver to fetch species
            val result = runNodeResolver(resolver, speciesGlobalId)

            assertNotNull(result)
            assertEquals(ref.name, result.getName())
        }
}
