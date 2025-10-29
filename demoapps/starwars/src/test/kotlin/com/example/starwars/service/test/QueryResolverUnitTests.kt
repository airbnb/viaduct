@file:Suppress("ForbiddenImport", "DEPRECATION")

package com.example.starwars.service.test

import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.characters.queries.AllCharactersQueryResolver
import com.example.starwars.modules.filmography.characters.queries.SearchCharacterQueryResolver
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import com.example.starwars.modules.filmography.films.queries.AllFilmsQueryResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmNodeResolver
import com.example.starwars.modules.universe.planets.models.PlanetsRepository
import com.example.starwars.modules.universe.planets.queries.AllPlanetsQueryResolver
import com.example.starwars.modules.universe.planets.resolvers.PlanetNodeResolver
import com.example.starwars.modules.universe.species.models.SpeciesRepository
import com.example.starwars.modules.universe.species.queries.AllSpeciesQueryResolver
import com.example.starwars.modules.universe.species.queries.SpeciesNodeQueryResolver
import com.example.starwars.modules.universe.vehicles.models.VehiclesRepository
import com.example.starwars.modules.universe.vehicles.queries.AllVehiclesQueryResolver
import com.example.starwars.modules.universe.vehicles.resolvers.VehicleNodeResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.CharacterSearchInput
import viaduct.api.grts.Film
import viaduct.api.grts.Planet
import viaduct.api.grts.Query_AllCharacters_Arguments
import viaduct.api.grts.Query_AllFilms_Arguments
import viaduct.api.grts.Query_AllPlanets_Arguments
import viaduct.api.grts.Query_AllSpecies_Arguments
import viaduct.api.grts.Query_AllVehicles_Arguments
import viaduct.api.grts.Query_SearchCharacter_Arguments
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

@OptIn(ExperimentalCoroutinesApi::class)
class QueryResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        SchemaFactory(DefaultCoroutineInterop)
            .fromResources("com.example.starwars", Regex(".*\\.graphqls"))

    lateinit var characterRepository: CharacterRepository
    lateinit var filmsRepository: FilmsRepository
    lateinit var speciesRepository: SpeciesRepository
    lateinit var vehiclesRepository: VehiclesRepository
    lateinit var planetsRepository: PlanetsRepository

    @BeforeEach
    fun setUp() {
        characterRepository = CharacterRepository()
        filmsRepository = FilmsRepository()
        speciesRepository = SpeciesRepository()
        vehiclesRepository = VehiclesRepository()
        planetsRepository = PlanetsRepository()
    }

    @Test
    fun `search character by name returns a matching character`(): Unit =
        runBlocking {
            val reference = characterRepository.findAll().first()
            val resolver = SearchCharacterQueryResolver(characterRepository)

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
            val reference = characterRepository.findAll().first()
            val resolver = SearchCharacterQueryResolver(characterRepository)

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

    // tag::test_limit_example[23] Test limit example
    @Test
    fun `allCharacters respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 3
            val resolver = AllCharactersQueryResolver(characterRepository)

            val args = Query_AllCharacters_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = characterRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
            assertEquals(ref.birthYear, first.getBirthYear())
        }

    @Test
    fun `allFilms respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 2
            val resolver = AllFilmsQueryResolver(filmsRepository)

            val args = Query_AllFilms_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = filmsRepository.getAllFilms().first()
            val first = result.first()!!
            assertEquals(ref.title, first.getTitle())
            assertEquals(ref.episodeID, first.getEpisodeID())
        }

    @Test
    fun `allPlanets respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 4
            val resolver = AllPlanetsQueryResolver(planetsRepository)

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
            val resolver = AllSpeciesQueryResolver(speciesRepository)

            val args = Query_AllSpecies_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = speciesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
        }

    @Test
    fun `allVehicles respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 1
            val resolver = AllVehiclesQueryResolver(vehiclesRepository)

            val args = Query_AllVehicles_Arguments.Builder(context)
                .limit(limit)
                .build()

            val result = runFieldResolver(
                resolver = resolver,
                arguments = args
            )

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = vehiclesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getName())
            assertEquals(ref.model, first.getModel())
        }

    @Test
    fun `vehicle by id returns the correct Vehicle using node resolver`(): Unit =
        runBlocking {
            val ref = vehiclesRepository.findAll().first()
            val resolver = VehicleNodeResolver(vehiclesRepository)

            // Create global ID for the vehicle
            val vehicleGlobalId = context.globalIDFor(Vehicle.Reflection, ref.id)

            // Use runNodeResolver to fetch vehicle
            val result = runNodeResolver(resolver, vehicleGlobalId)

            assertNotNull(result)
            assertEquals(ref.name, result.getName())
        }

    // tag::test_node_resolver_example[14] Test node resolver example
    @Test
    fun `film by id returns the correct Film using node resolver`(): Unit =
        runBlocking {
            val ref = filmsRepository.getAllFilms().first()
            val resolver = FilmNodeResolver(filmsRepository)

            // Create global ID for the film
            val filmGlobalId = context.globalIDFor(Film.Reflection, ref.id)

            // Use runNodeResolver to fetch film
            val result = runNodeResolver(resolver, filmGlobalId)

            assertNotNull(result)
            assertEquals(ref.title, result.getTitle())
        }

    @Test
    fun `planet by id returns the correct Planet using node resolver`(): Unit =
        runBlocking {
            val resolver = PlanetNodeResolver(planetsRepository)

            // Create global ID for the planet
            val planetGlobalId = context.globalIDFor(Planet.Reflection, "1")

            // Use runNodeResolver to fetch planet
            val result = runNodeBatchResolver(resolver, listOf(planetGlobalId))

            assertNotNull(result)
            assertEquals("Tatooine", result.first().get().getName())
        }

    @Test
    fun `species by id returns the correct Species using node resolver`(): Unit =
        runBlocking {
            val ref = speciesRepository.findAll().first()
            val resolver = SpeciesNodeQueryResolver(speciesRepository)

            // Create global ID for the species
            val speciesGlobalId = context.globalIDFor(Species.Reflection, ref.id)

            // Use runNodeResolver to fetch species
            val result = runNodeBatchResolver(resolver, listOf(speciesGlobalId))

            assertNotNull(result)
            assertEquals(ref.name, result.first().get().getName())
        }
}
