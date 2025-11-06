@file:Suppress("ForbiddenImport", "DEPRECATION")

package com.example.starwars.service.test

import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import com.example.starwars.modules.filmography.films.resolvers.FilmCharacterCountSummaryResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmDisplayTitleResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmProductionDetailsResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmSummaryResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.Film
import viaduct.api.mocks.MockGlobalID
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

/**
 * Integration tests for custom field resolvers on the Film type.
 *
 * These tests focus on the logic within each resolver, ensuring they return
 * the expected results given specific Film inputs.
 *
 * Note: Integration tests that cover full query execution and authorization
 * are located in QueryResolverUnitTests.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilmResolverUnitTests : DefaultAbstractResolverTestBase() {
    override fun getSchema(): ViaductSchema =
        SchemaFactory(DefaultCoroutineInterop)
            .fromResources("com.example.starwars", Regex(".*\\.graphqls"))

    private lateinit var filmCharactersRepository: FilmCharactersRepository

    @BeforeEach
    fun setUp() {
        filmCharactersRepository = FilmCharactersRepository()
    }

    @Test
    fun `FilmDisplayTitleResolver returns title`(): Unit =
        runBlocking {
            val resolver = FilmDisplayTitleResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Star Wars: A New Hope")
                    .build(),
            )

            assertEquals("Star Wars: A New Hope", result)
        }

    @Test
    fun `FilmSummaryResolver formats episode title and director`(): Unit =
        runBlocking {
            val resolver = FilmSummaryResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("The Empire Strikes Back")
                    .episodeID(5)
                    .director("Irvin Kershner")
                    .build(),
            )

            assertEquals("Episode 5: The Empire Strikes Back (Directed by Irvin Kershner)", result)
        }

    @Test
    fun `FilmProductionDetailsResolver formats release director and producers`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Return of the Jedi")
                    .director("Richard Marquand")
                    .producers(listOf("Howard Kazanjian", "George Lucas", "Rick McCallum"))
                    .releaseDate("1983-05-25")
                    .build(),
            )

            assertEquals(
                "Return of the Jedi was released on 1983-05-25, directed by Richard Marquand and produced by Howard Kazanjian, George Lucas, Rick McCallum",
                result
            )
        }

    @Test
    fun `FilmProductionDetailsResolver handles missing producers gracefully`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .title("Rogue One")
                    .director("Gareth Edwards")
                    .producers(null) // triggers "Unknown producers"
                    .releaseDate("2016-12-16")
                    .build(),
            )

            assertEquals(
                "Rogue One was released on 2016-12-16, directed by Gareth Edwards and produced by Unknown producers",
                result
            )
        }

    @Test
    fun `FilmCharacterCountSummaryResolver counts characters`(): Unit =
        runBlocking {
            val resolver = FilmCharacterCountSummaryResolver(filmCharactersRepository)

            val result = runFieldResolver(
                resolver = resolver,
                objectValue = Film.Builder(context)
                    .id(MockGlobalID(Film.Reflection, "1"))
                    .title("A New Hope")
                    .build(),
            )

            assertEquals("A New Hope features 5 main characters", result)
        }

    // Note: Node-based tests using runNodeResolver are now in QueryResolverUnitTests.kt
    // These tests demonstrate node fetches for Film and Character entities using proper NodeResolvers
}
