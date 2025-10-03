package viaduct.demoapp.starwars

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import viaduct.api.grts.Character
import viaduct.api.grts.Film

/**
 * Integration tests for all resolvers in the Star Wars GraphQL API.
 * These tests verify that all resolvers work correctly through REST calls to GraphQL endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResolverIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    private fun executeGraphQLQuery(query: String): JsonNode {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val request = mapOf("query" to query)
        val entity = HttpEntity(request, headers)

        val response = restTemplate.postForEntity(
            "http://localhost:$port/graphql",
            entity,
            String::class.java
        )

        return objectMapper.readTree(response.body)
    }

    @Nested
    inner class QueryResolvers {
        // Note: Individual node query tests are covered by StarWarsNodeResolversTest

        @Test
        fun `should resolve allCharacters list`() {
            val query = """
                query {
                    allCharacters(limit: 5) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val characters = response.path("data").path("allCharacters")

            assertTrue(characters.size() > 0)
        }

        @Test
        fun `should resolve allFilms list`() {
            val query = """
                query {
                    allFilms(limit: 3) {
                        id
                        title
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val films = response.path("data").path("allFilms")

            assertTrue(films.size() > 0)
        }

        @Test
        fun `should resolve searchCharacter query`() {
            val query = """
                query {
                    searchCharacter(search: { byName: "Luke" }) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val searchCharacterData = response.path("data").path("searchCharacter")
            val characterName = searchCharacterData.path("name").asText()

            assertNotNull(characterName)
            assertTrue(characterName.contains("Luke"), "Expected person name to contain 'Luke', got: '$characterName'")
        }
    }

    @Nested
    inner class FilmResolvers {
        @Test
        fun `should resolve all film fields`() {
            val encodedFilmId = Film.Reflection.globalId("1")
            val query = """
                query {
                    node(id: "$encodedFilmId") {
                        ... on Film {
                            id
                            title
                            episodeID
                            director
                            producers
                            releaseDate
                            openingCrawl
                            created
                            edited
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val filmId = response.path("data").path("node").path("id").asText()
            val filmTitle = response.path("data").path("node").path("title").asText()
            val filmDirector = response.path("data").path("node").path("director").asText()

            val expectedGlobalId = Film.Reflection.globalId("1")
            assertEquals(expectedGlobalId, filmId)
            assertNotNull(filmTitle)
            assertNotNull(filmDirector)
        }
    }

    @Nested
    inner class CharacterResolvers {
        @Test
        fun `should resolve all character fields`() {
            val encodedCharacterId = Character.Reflection.globalId("1")
            val query = """
                query {
                    node(id: "$encodedCharacterId") {
                        ... on Character {
                            id
                            name
                            birthYear
                            eyeColor
                            gender
                            hairColor
                            height
                            mass
                            homeworld {
                                id
                                name
                            }
                            species {
                                id
                                name
                            }
                            created
                            edited
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val characterId = response.path("data").path("node").path("id").asText()
            val characterName = response.path("data").path("node").path("name").asText()
            val homeworld = response.path("data").path("node").path("homeworld")

            // With Node interface, id field returns encoded GlobalID
            assertNotNull(characterId)
            assertTrue(characterId.isNotEmpty(), "Expected non-empty GlobalID, got: '$characterId'")
            assertNotNull(characterName)
            assertNotNull(homeworld)
        }

        @Test
        fun `should resolve person homeworld relationship`() {
            val encodedCharacterId = Character.Reflection.globalId("1")
            val query = """
                query {
                    node(id: "$encodedCharacterId") {
                        ... on Character {
                            id
                            name
                            homeworld {
                                id
                                name
                            }
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val homeworld = response.path("data").path("node").path("homeworld")

            assertNotNull(homeworld.path("id").asText())
        }

        @Test
        fun `should resolve person species relationship`() {
            val encodedCharacterId = Character.Reflection.globalId("1")
            val query = """
                query {
                    node(id: "$encodedCharacterId") {
                        ... on Character {
                            id
                            name
                            species {
                                id
                                name
                            }
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val species = response.path("data").path("node").path("species")

            assertNotNull(species)
        }
    }

    @Nested
    inner class CrossResolverIntegrationTests {
        @Test
        fun `should handle multi-type queries across all resolvers`() {
            val encodedCharacterId = Character.Reflection.globalId("1")
            val encodedFilmId = Film.Reflection.globalId("1")
            val query = """
                query {
                    character: node(id: "$encodedCharacterId") {
                        ... on Character {
                            id
                            name
                        }
                    }
                    film: node(id: "$encodedFilmId") {
                        ... on Film {
                            id
                            title
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val characterId = response.path("data").path("character").path("id").asText()
            val filmId = response.path("data").path("film").path("id").asText()

            // With Node interface, person id returns encoded GlobalID
            val expectedCharacterGlobalId = Character.Reflection.globalId("1")
            assertEquals(expectedCharacterGlobalId, characterId)
            // Film now also uses GlobalID format (implements Node interface)
            val expectedFilmGlobalId = Film.Reflection.globalId("1")
            assertEquals(expectedFilmGlobalId, filmId)
        }

        @Test
        fun `should handle invalid IDs gracefully`() {
            val encodedInvalidId = Character.Reflection.globalId("invalid")
            val query = """
                query {
                    node(id: "$encodedInvalidId") {
                        ... on Character {
                            id
                            name
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val person = response.path("data").path("node")

            assertTrue(person.isNull)
        }

        @Test
        fun `should resolve complex nested relationships across resolvers`() {
            val encodedCharacterId = Character.Reflection.globalId("1")
            val encodedFilmId = Film.Reflection.globalId("1")
            val query = """
                query {
                    character: node(id: "$encodedCharacterId") {
                        ... on Character {
                            id
                            name
                            homeworld {
                                id
                                name
                            }
                        }
                    }
                    film: node(id: "$encodedFilmId") {
                        ... on Film {
                            id
                            title
                            director
                        }
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val personHomeworld = response.path("data").path("character").path("homeworld")
            val filmDirector = response.path("data").path("film").path("director").asText()

            assertNotNull(personHomeworld)
            assertNotNull(filmDirector)
        }
    }

    @Nested
    inner class MutationResolvers {
        @Test
        fun `should resolve createCharacter mutation`() {
            val query = """
                mutation {
                    createCharacter(input: {
                        name: "Chewbacca"
                        birthYear: "200BBY"
                        eyeColor: "blue"
                        gender: "male"
                        hairColor: "brown"
                        height: 228
                        mass: 112
                        homeworldId: "6"
                        speciesId: "2"
                    }) {
                        id
                        name
                        birthYear
                        eyeColor
                        gender
                        hairColor
                        height
                        mass
                        homeworld {
                            id
                            name
                        }
                        species {
                            id
                            name
                        }
                        displayName
                        displaySummary
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)
            val createdCharacter = response.path("data").path("createCharacter")

            assertNotNull(createdCharacter)
            assertNotNull(createdCharacter.path("id").asText())
            assertEquals("Chewbacca", createdCharacter.path("name").asText())
            assertEquals("200BBY", createdCharacter.path("birthYear").asText())
            assertEquals("blue", createdCharacter.path("eyeColor").asText())
            assertEquals("male", createdCharacter.path("gender").asText())
            assertEquals("brown", createdCharacter.path("hairColor").asText())
            assertEquals(228, createdCharacter.path("height").asInt())
            assertEquals(112.0, createdCharacter.path("mass").asDouble())
            assertNotNull(createdCharacter.path("homeworld").path("id").asText())
            assertEquals("Kashyyyk", createdCharacter.path("homeworld").path("name").asText())
            assertNotNull(createdCharacter.path("species").path("id").asText())
            assertEquals("Wookiee", createdCharacter.path("species").path("name").asText())
            assertEquals("Chewbacca", createdCharacter.path("displayName").asText())
            assertEquals("Chewbacca (200BBY)", createdCharacter.path("displaySummary").asText())
        }

        @Test
        fun `should fail to resolve createCharacter if speciesId is invalid`() {
            val query = """
                mutation {
                    createCharacter(input: {
                        name: "Chewbacca"
                        birthYear: "200BBY"
                        eyeColor: "blue"
                        gender: "male"
                        hairColor: "brown"
                        height: 228
                        mass: 112
                        homeworldId: "5"
                        speciesId: "invalid-speciesId"
                    }) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Species with ID invalid-speciesId not found"))
        }

        @Test
        fun `should fail to resolve createCharacter if homeworldId is invalid`() {
            val query = """
                mutation {
                    createCharacter(input: {
                        name: "Chewbacca"
                        birthYear: "200BBY"
                        eyeColor: "blue"
                        gender: "male"
                        hairColor: "brown"
                        height: 228
                        mass: 112
                        homeworldId: "invalid-homeworldId"
                        speciesId: "2"
                    }) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Planet with ID invalid-homeworldId not found"))
        }

        @Test
        fun `should resolve updateCharacterName mutation`() {
            // First, create a new character to delete
            val createCharacterQuery = """
                mutation {
                    createCharacter(input: {
                        name: "Chewbacca"
                        birthYear: "200BBY"
                        eyeColor: "blue"
                        gender: "male"
                        hairColor: "brown"
                        height: 228
                        mass: 112
                        homeworldId: "5"
                        speciesId: "2"
                    }) {
                        id
                    }
                }
            """.trimIndent()

            val createResult = executeGraphQLQuery(createCharacterQuery)

            val query = """
                mutation {
                    updateCharacterName(id: "${createResult.path("data").path("createCharacter").path("id").asText()}", name: "Chewbacca Updated") {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            assertNotNull(updatedCharacter)
            assertEquals("Chewbacca Updated", updatedCharacter.path("name").asText())
        }

        @Test
        fun `should fail to resolve updateCharacterName if id is invalid`() {
            val query = """
                mutation {
                    updateCharacterName(id: "${Character.Reflection.globalId("9999")}", name: "Nonexistent Character") {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            assertTrue(updatedCharacter.isNull)
            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Character with ID 9999 not found"))
        }

        @Test
        fun `should resolve addCharacterToFilm mutation`() {
            // First, create a new character to add to the film
            `should resolve createCharacter mutation`()

            val query = """
    mutation {
        addCharacterToFilm(input: {
            filmId: "1"
            characterId: "6"
        }) {
            film {
                id
                title
            }
            character {
                id
                name
            }

        }
    }

            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            assertNotNull(updatedFilm)
            assertEquals("A New Hope", updatedFilm.path("film").path("title").asText())
            assertEquals("Chewbacca", updatedFilm.path("character").path("name").asText())
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if filmId is invalid`() {
            val query = """
    mutation {
        addCharacterToFilm(input: {
            filmId: "9999"
            characterId: "1"
        }) {
            film {
                id
                title
                }
            character {
                id
                name
                }
        }
    }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            assertTrue(updatedFilm.isNull)
            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Film with ID 9999 not found"))
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if characterId is invalid`() {
            val query = """
    mutation {
        addCharacterToFilm(input: {
            filmId: "1"
            characterId: "9999"
        }) {
            film {
                id
                title
            }
            character {
                id
                name
            }
        }
    }
            """.trimIndent()
            val response = executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            assertTrue(updatedFilm.isNull)
            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Character with ID 9999 not found"))
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if character is already in film`() {
            val query = """
    mutation {
        addCharacterToFilm(input: {
            filmId: "1"
            characterId: "1"
        }) {
            film {
                id
                title
            }
            character {
                id
                name
            }
        }
    }
            """.trimIndent()
            val response = executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            assertTrue(updatedFilm.isNull)
            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Character with ID 1 is already in film with ID 1"))
        }

        @Test
        fun `should resolve deleteCharacter mutation`() {
            // First, create a new character to delete
            val createCharacterQuery = """
                mutation {
                    createCharacter(input: {
                        name: "Chewbacca"
                        birthYear: "200BBY"
                        eyeColor: "blue"
                        gender: "male"
                        hairColor: "brown"
                        height: 228
                        mass: 112
                        homeworldId: "5"
                        speciesId: "2"
                    }) {
                        id
                    }
                }
            """.trimIndent()
            val createResult = executeGraphQLQuery(createCharacterQuery)

            val query = """
    mutation {
        deleteCharacter(id: "${createResult.path("data").path("createCharacter").path("id").asText()}")
        }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val deleteResult = response.path("data").path("deleteCharacter").asBoolean()
            assertTrue(deleteResult, "Expected deleteCharacter to return true")
        }

        @Test
        fun `should fail to resolve deleteCharacter if id is invalid`() {
            val query = """
    mutation {
        deleteCharacter(id: "${Character.Reflection.globalId("9999")}")
    }
            """.trimIndent()

            val response = executeGraphQLQuery(query)

            val deleteResult = response.path("data").path("deleteCharacter")
            assertTrue(deleteResult.isNull)
            val errors = response.path("errors")
            assertNotNull(errors)
            assertTrue(errors.isArray && errors.size() == 1)
            val errorMessage = errors[0].path("message").asText()
            assertTrue(errorMessage.contains("Character with ID 9999 not found"))
        }
    }
}
