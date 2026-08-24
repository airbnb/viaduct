@file:Suppress("ForbiddenImport")

package com.example.starwars.service.test

import com.example.starwars.modules.filmography.films.models.FilmCastData
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import com.example.starwars.modules.filmography.films.resolvers.FilmCastDataResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmDisplayTitleResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmProductionDetailsResolver
import com.example.starwars.modules.filmography.films.resolvers.FilmSummaryResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.grts.Film
import viaduct.api.grts.FilmProductionDetails
import viaduct.api.select.SelectionSet
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.errors.UnsetFieldException

/**
 * Integration tests for custom field resolvers on the Film type.
 *
 * These tests focus on the logic within each resolver, ensuring they return
 * the expected results given specific Film inputs.
 *
 * Note: Integration tests that cover full query execution and authorization
 * are located in QueryResolverUnitTests.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class, InternalApi::class)
class FilmResolverUnitTests : ResolverTestBase() {
    private lateinit var filmCharactersRepository: FilmCharactersRepository

    @BeforeEach
    fun setUp() {
        filmCharactersRepository = FilmCharactersRepository()
    }

    @Test
    fun `FilmDisplayTitleResolver returns title`(): Unit =
        runBlocking {
            val resolver = FilmDisplayTitleResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Film.of(context) { title("Star Wars: A New Hope") }
            }

            assertEquals("Star Wars: A New Hope", result)
        }

    @Test
    fun `FilmSummaryResolver formats episode title and director`(): Unit =
        runBlocking {
            val resolver = FilmSummaryResolver()

            val result = runFieldResolver(resolver) {
                objectValue = Film.of(context) {
                    title("The Empire Strikes Back")
                    episodeID(5)
                    director("Irvin Kershner")
                }
            }

            assertEquals("Episode 5: The Empire Strikes Back (Directed by Irvin Kershner)", result)
        }

    @Test
    fun `FilmProductionDetailsResolver returns only selected fields`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(resolver) {
                selections = productionDetailsSelections("title director")
                objectValue = Film.of(context) {
                    title("Return of the Jedi")
                    director("Richard Marquand")
                    producers(listOf("Howard Kazanjian", "George Lucas", "Rick McCallum"))
                    releaseDate("1983-05-25")
                }
            }

            assertNotNull(result)
            assertEquals("Return of the Jedi", result!!.getTitleOrThrow())
            assertEquals("Richard Marquand", result.getDirectorOrThrow())
            assertThrows(UnsetFieldException::class.java) { result.getProducersOrThrow() }
            assertThrows(UnsetFieldException::class.java) { result.getReleaseDateOrThrow() }
        }

    @Test
    fun `FilmProductionDetailsResolver preserves null selected fields`(): Unit =
        runBlocking {
            val resolver = FilmProductionDetailsResolver()

            val result = runFieldResolver(resolver) {
                selections = productionDetailsSelections("producers")
                objectValue = Film.of(context) {
                    title("Rogue One")
                    director("Gareth Edwards")
                    producers(null)
                    releaseDate("2016-12-16")
                }
            }

            assertNotNull(result)
            assertNull(result!!.getProducersOrThrow())
        }

    private fun productionDetailsSelections(fields: String): SelectionSet<FilmProductionDetails> = mkSelectionSetFactory().selectionsOn(FilmProductionDetails.Reflection, fields, emptyMap())

    @Test
    fun `FilmCastDataResolver returns character IDs from repository`(): Unit =
        runBlocking {
            val resolver = FilmCastDataResolver(filmCharactersRepository)

            val result = runFieldResolver(resolver) {
                objectValue = Film.of(context) {
                    id(GlobalID(Film.Reflection, "1"))
                    title("A New Hope")
                }
            }

            assertEquals(FilmCastData(listOf("1", "2", "3", "4", "5")), result)
        }

    // Note: Node-based tests using runNodeResolver are now in QueryResolverUnitTests.kt
    // These tests demonstrate node fetches for Film and Character entities using proper NodeResolvers
}
