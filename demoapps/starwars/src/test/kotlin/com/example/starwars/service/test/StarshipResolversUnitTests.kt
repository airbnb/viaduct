@file:Suppress("ForbiddenImport")

package com.example.starwars.service.test

import com.example.starwars.modules.universe.starships.models.StarshipsRepository
import com.example.starwars.modules.universe.starships.queries.AllStarshipsQueryResolver
import com.example.starwars.modules.universe.starships.resolvers.StarshipNodeResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Query
import viaduct.api.grts.Query_AllStarships_Arguments
import viaduct.api.grts.Starship
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi

/**
 * Integration tests for custom resolvers related to Starship type.
 *
 * These tests focus on the logic within each resolver, ensuring they return
 * the expected results given specific inputs.
 *
 * Note: Integration tests that cover full query execution and authorization
 * are located in QueryResolverUnitTests.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class)
class StarshipResolversUnitTests : ResolverTestBase() {
    private lateinit var starshipsRepository: StarshipsRepository

    @BeforeEach
    fun setUp() {
        starshipsRepository = StarshipsRepository()
    }

    private fun queryObj() = Query.of(context) { }

    @Test
    fun `AllStarshipsResolver returns default page size when limit is not provided`(): Unit =
        runBlocking {
            val resolver = AllStarshipsQueryResolver(starshipsRepository)
            val args = Query_AllStarships_Arguments.of(context) { }

            val result = runFieldResolver(resolver) {
                objectValue = queryObj()
                queryValue = queryObj()
                arguments = args
            }

            assertNotNull(result)
            assertEquals(2, result!!.size)
        }

    @Test
    fun `AllStarshipsResolver respects custom limit and maps fields`(): Unit =
        runBlocking {
            val resolver = AllStarshipsQueryResolver(starshipsRepository)
            val limit = 2
            val args = Query_AllStarships_Arguments.of(context) { limit(limit) }

            val result = runFieldResolver(resolver) {
                objectValue = queryObj()
                queryValue = queryObj()
                arguments = args
            }

            assertNotNull(result)
            assertEquals(2, result!!.size)

            val grt = result.first()!!
            assertEquals("Millennium Falcon", grt.getName())
        }

    @Test
    fun `starship by id returns the correct Starship using node resolver`(): Unit =
        runBlocking {
            val resolver = StarshipNodeResolver(starshipsRepository)
            val starshipId = "1" // Millennium Falcon ID

            val starshipGlobalId = globalIDFor(Starship.Reflection, starshipId)
            val result = runNodeResolver(resolver) { id = starshipGlobalId }

            assertNotNull(result)
            assertEquals("Millennium Falcon", result.getName())
            assertEquals("YT-1300 light freighter", result.getModel())
        }

    @Test
    fun `starship by id returns the correct X-wing using node resolver`(): Unit =
        runBlocking {
            val resolver = StarshipNodeResolver(starshipsRepository)
            val starshipId = "2" // X-wing ID

            val starshipGlobalId = globalIDFor(Starship.Reflection, starshipId)
            val result = runNodeResolver(resolver) { id = starshipGlobalId }

            assertNotNull(result)
            assertEquals("X-wing", result.getName())
            assertEquals("T-65 X-wing", result.getModel())
        }
}
