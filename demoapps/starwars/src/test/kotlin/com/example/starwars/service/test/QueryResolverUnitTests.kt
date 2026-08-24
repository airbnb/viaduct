@file:Suppress("ForbiddenImport")

package com.example.starwars.service.test

import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.characters.queries.AllCharactersConnectionQueryResolver
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.CharacterSearchInput
import viaduct.api.grts.Film
import viaduct.api.grts.Planet
import viaduct.api.grts.Query_AllCharactersConnection_Arguments
import viaduct.api.grts.Query_AllCharacters_Arguments
import viaduct.api.grts.Query_AllFilms_Arguments
import viaduct.api.grts.Query_AllPlanets_Arguments
import viaduct.api.grts.Query_AllSpecies_Arguments
import viaduct.api.grts.Query_AllVehicles_Arguments
import viaduct.api.grts.Query_SearchCharacter_Arguments
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle
import viaduct.api.select.SelectionSet
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.errors.UnsetFieldException

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class, InternalApi::class)
class QueryResolverUnitTests : ResolverTestBase() {
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

            val args = Query_SearchCharacter_Arguments.of(context) {
                search(CharacterSearchInput.of(context) { byName(reference.name.substring(0, 2)) })
            }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            result!!
            assertEquals(reference.name, result.getNameOrThrow())
            assertEquals(reference.birthYear, result.getBirthYearOrThrow())
        }

    @Test
    fun `search character by id returns exact character`(): Unit =
        runBlocking {
            val reference = characterRepository.findAll().first()
            val resolver = SearchCharacterQueryResolver(characterRepository)

            val gid = globalIDFor(Character.Reflection, reference.id)

            val args = Query_SearchCharacter_Arguments.of(context) {
                search(CharacterSearchInput.of(context) { byId(gid) })
            }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(reference.name, result!!.getNameOrThrow())
        }

    // tag::test_limit_example[19] Test limit example
    @Test
    fun `allCharacters respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 3
            val resolver = AllCharactersQueryResolver(characterRepository)

            val args = Query_AllCharacters_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = characterRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getNameOrThrow())
            assertEquals(ref.birthYear, first.getBirthYearOrThrow())
        }

    @Test
    fun `allFilms respects limit and returns node references`(): Unit =
        runBlocking {
            val limit = 2
            val resolver = AllFilmsQueryResolver(filmsRepository)

            val args = Query_AllFilms_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = filmsRepository.getAllFilms().first()
            val first = result.first()!!
            assertEquals(ref.id, first.getIdOrThrow().internalID)
            assertThrows(UnsetFieldException::class.java) { first.getTitleOrThrow() }
        }

    @Test
    fun `allPlanets respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 4
            val resolver = AllPlanetsQueryResolver(planetsRepository)

            val args = Query_AllPlanets_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val first = result.first()!!
            assertEquals("Tatooine", first.getNameOrThrow())
        }

    @Test
    fun `allSpecies respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 1
            val resolver = AllSpeciesQueryResolver(speciesRepository)

            val args = Query_AllSpecies_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = speciesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getNameOrThrow())
        }

    @Test
    fun `allVehicles respects limit and maps fields`(): Unit =
        runBlocking {
            val limit = 1
            val resolver = AllVehiclesQueryResolver(vehiclesRepository)

            val args = Query_AllVehicles_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals(limit, result!!.size)
            val ref = vehiclesRepository.findAll().first()
            val first = result.first()!!
            assertEquals(ref.name, first.getNameOrThrow())
            assertEquals(ref.model, first.getModelOrThrow())
        }

    @Test
    fun `vehicle by id returns the correct Vehicle using node resolver`(): Unit =
        runBlocking {
            val ref = vehiclesRepository.findAll().first()
            val resolver = VehicleNodeResolver(vehiclesRepository)

            // Create global ID for the vehicle
            val vehicleGlobalId = globalIDFor(Vehicle.Reflection, ref.id)

            // Use runNodeResolver to fetch vehicle
            val result = runNodeResolver(resolver) { id = vehicleGlobalId }

            assertNotNull(result)
            assertEquals(ref.name, result.getNameOrThrow())
        }

    // tag::test_node_resolver_example[12] Test node resolver example
    @Test
    fun `film by id returns the correct Film using node resolver`(): Unit =
        runBlocking {
            val ref = filmsRepository.getAllFilms().first()

            val result = runNodeResolver(FilmNodeResolver(filmsRepository)) {
                id = globalIDFor(Film.Reflection, ref.id)
                selections = filmSelections("openingCrawl")
            }

            assertNotNull(result)
            assertEquals(ref.openingCrawl, result.getOpeningCrawlOrThrow())
            assertThrows(UnsetFieldException::class.java) { result.getTitleOrThrow() }
        }

    private fun filmSelections(fields: String): SelectionSet<Film> = mkSelectionSetFactory().selectionsOn(Film.Reflection, fields, emptyMap())

    @Test
    fun `planet by id returns the correct Planet using node resolver`(): Unit =
        runBlocking {
            val resolver = PlanetNodeResolver(planetsRepository)

            // Create global ID for the planet
            val planetGlobalId = globalIDFor(Planet.Reflection, "1")

            // Use runNodeResolver to fetch planet
            val result = runNodeBatchResolver(resolver) { ids = listOf(planetGlobalId) }

            assertNotNull(result)
            assertEquals("Tatooine", result.values.first().get().getNameOrThrow())
        }

    @Test
    fun `species by id returns the correct Species using node resolver`(): Unit =
        runBlocking {
            val ref = speciesRepository.findAll().first()
            val resolver = SpeciesNodeQueryResolver(speciesRepository)

            // Create global ID for the species
            val speciesGlobalId = globalIDFor(Species.Reflection, ref.id)

            // Use runNodeResolver to fetch species
            val result = runNodeBatchResolver(resolver) { ids = listOf(speciesGlobalId) }

            assertNotNull(result)
            assertEquals(ref.name, result.values.first().get().getNameOrThrow())
        }

    @Test
    fun `allCharactersConnection paginates forward and reports correct page info`(): Unit =
        runBlocking {
            val resolver = AllCharactersConnectionQueryResolver(characterRepository)

            // Request first 3 of 5 characters
            val firstPageArgs = Query_AllCharactersConnection_Arguments.of(context) { first(3) }

            val firstPage = runFieldResolver(resolver) { arguments = firstPageArgs }

            assertNotNull(firstPage)
            firstPage!!
            assertEquals(3, firstPage.getEdgesOrThrow()!!.size)
            val endCursor = firstPage.getEdgesOrThrow()!!.last()!!.getCursorOrThrow()
            assertNotNull(endCursor)

            // Request next 3 from endCursor — only 2 characters remain
            val secondPageArgs = Query_AllCharactersConnection_Arguments.of(context) {
                first(3)
                after(endCursor)
            }

            val secondPage = runFieldResolver(resolver) { arguments = secondPageArgs }

            assertNotNull(secondPage)
            assertEquals(2, secondPage!!.getEdgesOrThrow()!!.size)
        }
}
