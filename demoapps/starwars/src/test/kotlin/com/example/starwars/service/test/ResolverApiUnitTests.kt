@file:Suppress("ForbiddenImport")

package com.example.starwars.service.test

import com.example.starwars.common.SecurityAccessContext
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository
import com.example.starwars.modules.filmography.characters.models.CharacterRepository
import com.example.starwars.modules.filmography.characters.mutations.CreateCharacterMutation
import com.example.starwars.modules.filmography.characters.mutations.DeleteCharacterMutation
import com.example.starwars.modules.filmography.characters.mutations.UpdateCharacterNameMutation
import com.example.starwars.modules.filmography.characters.resolvers.CharacterFilmCountResolver
import com.example.starwars.modules.filmography.characters.resolvers.CharacterRichSummaryResolver
import com.example.starwars.modules.filmography.films.models.FilmCharactersRepository
import com.example.starwars.modules.filmography.films.models.FilmsRepository
import com.example.starwars.modules.filmography.films.mutations.AddCharacterToFilmMutation
import com.example.starwars.modules.filmography.films.resolvers.FilmMainCharactersResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.grts.AddCharacterToFilmInput
import viaduct.api.grts.Character
import viaduct.api.grts.CreateCharacterInput
import viaduct.api.grts.Film
import viaduct.api.grts.Mutation_AddCharacterToFilm_Arguments
import viaduct.api.grts.Mutation_CreateCharacter_Arguments
import viaduct.api.grts.Mutation_DeleteCharacter_Arguments
import viaduct.api.grts.Mutation_UpdateCharacterName_Arguments
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi

/**
 * Tests for the testing APIs not covered by the per-resolver unit tests:
 *   - runFieldBatchResolver  (batch field resolvers)
 *   - runMutationFieldResolver  (mutation resolvers)
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class)
class ResolverApiUnitTests : ResolverTestBase() {
    private lateinit var characterRepository: CharacterRepository
    private lateinit var characterFilmsRepository: CharacterFilmsRepository
    private lateinit var filmCharactersRepository: FilmCharactersRepository
    private lateinit var filmsRepository: FilmsRepository
    private lateinit var adminAccess: SecurityAccessContext

    @BeforeEach
    fun setUp() {
        characterRepository = CharacterRepository()
        characterFilmsRepository = CharacterFilmsRepository()
        filmCharactersRepository = FilmCharactersRepository()
        filmsRepository = FilmsRepository()
        adminAccess = object : SecurityAccessContext() {
            override fun <T> validateAccess(block: () -> T): T = block()
        }
    }

    // -------------------------------------------------------------------------
    // runFieldBatchResolver
    // -------------------------------------------------------------------------

    // tag::field_batch_resolver_example[12] Field batch resolver test
    @Test
    fun `runFieldBatchResolver returns film count for each character in batch`(): Unit =
        runBlocking {
            val results = runFieldBatchResolver(CharacterFilmCountResolver(characterFilmsRepository)) {
                objectValues = characterRepository.findAll().take(3).map { char ->
                    Character.of(context) { id(globalIDFor(Character.Reflection, char.id)) }
                }
            }

            assertEquals(3, results.size)
            results.forEach { fv -> assertEquals(3, fv.get()) }
        }

    @Test
    fun `runFieldBatchResolver returns zero film count for unknown character`(): Unit =
        runBlocking {
            val resolver = CharacterFilmCountResolver(characterFilmsRepository)
            val unknown = Character.of(context) {
                id(globalIDFor(Character.Reflection, "999"))
            }

            val results = runFieldBatchResolver(resolver) {
                objectValues = listOf(unknown)
            }

            assertEquals(1, results.size)
            assertEquals(0, results.first().get())
        }

    @Test
    fun `runFieldBatchResolver returns rich summary for multiple characters`(): Unit =
        runBlocking {
            val resolver = CharacterRichSummaryResolver(characterRepository, characterFilmsRepository)
            val characters = characterRepository.findAll().take(2).map { char ->
                Character.of(context) {
                    id(globalIDFor(Character.Reflection, char.id))
                    name(char.name)
                    birthYear(char.birthYear)
                }
            }

            val results = runFieldBatchResolver(resolver) {
                objectValues = characters
            }

            assertEquals(2, results.size)
            results.forEach { fv ->
                val summary = fv.get()
                assertNotNull(summary)
                assertTrue(summary!!.contains("films"), "Expected 'films' in summary: $summary")
            }
        }

    @Test
    fun `runFieldBatchResolver for FilmMainCharactersResolver returns characters per film`(): Unit =
        runBlocking {
            val resolver = FilmMainCharactersResolver(characterRepository, filmCharactersRepository)
            val films = filmsRepository.getAllFilms().take(2).map { film ->
                Film.of(context) { id(globalIDFor(Film.Reflection, film.id)) }
            }

            val results = runFieldBatchResolver(resolver) {
                objectValues = films
            }

            assertEquals(2, results.size)
            results.forEach { fv ->
                val characters = fv.get()
                assertEquals(5, characters?.size)
            }
        }

    // -------------------------------------------------------------------------
    // runMutationFieldResolver
    // -------------------------------------------------------------------------

    // tag::mutation_resolver_example[18] Mutation resolver test
    @Test
    fun `runMutationFieldResolver CreateCharacter creates and returns new character`(): Unit =
        runBlocking {
            val resolver = CreateCharacterMutation(characterRepository, adminAccess)
            val input = CreateCharacterInput.of(context) {
                name("Ahsoka Tano")
                birthYear("36BBY")
            }
            val args = Mutation_CreateCharacter_Arguments.of(context) { input(input) }

            val result = runMutationFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals("Ahsoka Tano", result!!.getName())
            assertEquals("36BBY", result.getBirthYear())
        }

    @Test
    fun `runMutationFieldResolver CreateCharacter throws when access denied`(): Unit =
        runBlocking {
            val noAccess = SecurityAccessContext() // default: no admin
            val resolver = CreateCharacterMutation(characterRepository, noAccess)
            val input = CreateCharacterInput.of(context) { name("Palpatine") }
            val args = Mutation_CreateCharacter_Arguments.of(context) { input(input) }

            assertThrows(SecurityException::class.java) {
                runBlocking {
                    runMutationFieldResolver(resolver) { arguments = args }
                }
            }
        }

    @Test
    fun `runMutationFieldResolver UpdateCharacterName renames existing character`(): Unit =
        runBlocking {
            val resolver = UpdateCharacterNameMutation(characterRepository, adminAccess)
            val character = characterRepository.findAll().first()
            val globalId = globalIDFor(Character.Reflection, character.id)
            val args = Mutation_UpdateCharacterName_Arguments.of(context) {
                id(globalId)
                name("Anakin Skywalker")
            }

            val result = runMutationFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals("Anakin Skywalker", result!!.getName())
        }

    @Test
    fun `runMutationFieldResolver UpdateCharacterName throws for unknown id`(): Unit =
        runBlocking {
            val resolver = UpdateCharacterNameMutation(characterRepository, adminAccess)
            val unknownId = globalIDFor(Character.Reflection, "999")
            val args = Mutation_UpdateCharacterName_Arguments.of(context) {
                id(unknownId)
                name("Nobody")
            }

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    runMutationFieldResolver(resolver) { arguments = args }
                }
            }
        }

    @Test
    fun `runMutationFieldResolver DeleteCharacter removes character and returns true`(): Unit =
        runBlocking {
            val resolver = DeleteCharacterMutation(
                characterRepository,
                characterFilmsRepository,
                filmCharactersRepository,
                adminAccess
            )
            val character = characterRepository.findAll().first()
            val globalId = globalIDFor(Character.Reflection, character.id)
            val args = Mutation_DeleteCharacter_Arguments.of(context) { id(globalId) }

            val result = runMutationFieldResolver(resolver) {
                arguments = args
            }

            assertTrue(result == true)
            assertNull(characterRepository.findById(character.id))
        }

    @Test
    fun `runMutationFieldResolver AddCharacterToFilm links character to film`(): Unit =
        runBlocking {
            // Use a character not yet in film 1 — add a fresh one first
            val newCharacter = characterRepository.add(
                com.example.starwars.modules.filmography.characters.models.Character(
                    id = "",
                    name = "Wedge Antilles",
                    birthYear = "21BBY",
                    eyeColor = "brown",
                    gender = "male",
                    hairColor = "brown",
                    height = 170,
                    mass = 77f,
                    homeworldId = null,
                    speciesId = null,
                )
            )
            val characterGlobalId = globalIDFor(Character.Reflection, newCharacter.id)
            val filmGlobalId = globalIDFor(Film.Reflection, "1")

            val resolver = AddCharacterToFilmMutation(
                characterFilmsRepository,
                filmCharactersRepository,
                filmsRepository,
                characterRepository,
                adminAccess
            )
            val input = AddCharacterToFilmInput.of(context) {
                filmId(filmGlobalId)
                characterId(characterGlobalId)
            }
            val args = Mutation_AddCharacterToFilm_Arguments.of(context) { input(input) }

            val result = runMutationFieldResolver(resolver) {
                arguments = args
            }

            assertNotNull(result)
            assertEquals("Wedge Antilles", result!!.getCharacter()!!.getName())
        }
}
