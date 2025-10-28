package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.Film
import viaduct.api.grts.Species

/**
 * Integration tests for GraphQL resolvers.
 *
 * These tests cover queries and mutations across multiple resolvers,
 * ensuring end-to-end functionality of the GraphQL API.
 */
@MicronautTest
class ResolverIntegrationTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

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

            val response = client.executeGraphQLQuery(query)
            val characters = response.path("data").path("allCharacters")

            (characters.size() > 0) shouldBe true
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

            val response = client.executeGraphQLQuery(query)
            val films = response.path("data").path("allFilms")

            (films.size() > 0) shouldBe true
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

            val response = client.executeGraphQLQuery(query)
            val searchCharacterData = response.path("data").path("searchCharacter")
            val characterName = searchCharacterData.path("name").asText()

            characterName shouldNotBe null
            characterName shouldContain "Luke"
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

            val response = client.executeGraphQLQuery(query)
            val filmId = response.path("data").path("node").path("id").asText()
            val filmTitle = response.path("data").path("node").path("title").asText()
            val filmDirector = response.path("data").path("node").path("director").asText()

            val expectedGlobalId = Film.Reflection.globalId("1")
            filmId shouldBe expectedGlobalId
            filmTitle shouldNotBe null
            filmDirector shouldNotBe null
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

            val response = client.executeGraphQLQuery(query)
            val characterId = response.path("data").path("node").path("id").asText()
            val characterName = response.path("data").path("node").path("name").asText()
            val homeworld = response.path("data").path("node").path("homeworld")

            // With Node interface, id field returns encoded GlobalID
            characterId shouldNotBe null
            characterId.shouldNotBeEmpty()
            characterName shouldNotBe null
            homeworld shouldNotBe null
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

            val response = client.executeGraphQLQuery(query)
            val homeworld = response.path("data").path("node").path("homeworld")

            homeworld.path("id").asText() shouldNotBe null
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

            val response = client.executeGraphQLQuery(query)
            val species = response.path("data").path("node").path("species")

            species shouldNotBe null
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

            val response = client.executeGraphQLQuery(query)
            val characterId = response.path("data").path("character").path("id").asText()
            val filmId = response.path("data").path("film").path("id").asText()

            // With Node interface, person id returns encoded GlobalID
            val expectedCharacterGlobalId = Character.Reflection.globalId("1")
            characterId shouldBe expectedCharacterGlobalId
            // Film now also uses GlobalID format (implements Node interface)
            val expectedFilmGlobalId = Film.Reflection.globalId("1")
            filmId shouldBe expectedFilmGlobalId
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

            val response = client.executeGraphQLQuery(query)
            val person = response.path("data").path("node")

            person.isMissingNode shouldBe true
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

            val response = client.executeGraphQLQuery(query)
            val personHomeworld = response.path("data").path("character").path("homeworld")
            val filmDirector = response.path("data").path("film").path("director").asText()

            personHomeworld shouldNotBe null
            filmDirector shouldNotBe null
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
                        homeworldId: "${Character.Reflection.globalId("6")}"
                        speciesId: "${Species.Reflection.globalId("2")}"
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

            val response = client.executeGraphQLQuery(query)
            val createdCharacter = response.path("data").path("createCharacter")

            createdCharacter shouldNotBe null
            createdCharacter.path("id").asText() shouldNotBe null
            createdCharacter.path("name").asText() shouldBe "Chewbacca"
            createdCharacter.path("birthYear").asText() shouldBe "200BBY"
            createdCharacter.path("eyeColor").asText() shouldBe "blue"
            createdCharacter.path("gender").asText() shouldBe "male"
            createdCharacter.path("hairColor").asText() shouldBe "brown"
            createdCharacter.path("height").asInt() shouldBe 228
            createdCharacter.path("mass").asDouble() shouldBe 112.0
            createdCharacter.path("homeworld").path("id").asText() shouldNotBe null
            createdCharacter.path("homeworld").path("name").asText() shouldBe "Kashyyyk"
            createdCharacter.path("species").path("id").asText() shouldNotBe null
            createdCharacter.path("species").path("name").asText() shouldBe "Wookiee"
            createdCharacter.path("displayName").asText() shouldBe "Chewbacca"
            createdCharacter.path("displaySummary").asText() shouldBe "Chewbacca (200BBY)"
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
                        homeworldId: "${Character.Reflection.globalId("5")}"
                        speciesId: "invalid-speciesId"
                    }) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "IllegalArgumentException: Illegal base64 character"
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
                        speciesId: "${Species.Reflection.globalId("2")}"
                    }) {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "IllegalArgumentException: Illegal base64 character"
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
                        homeworldId: "${Character.Reflection.globalId("5")}"
                        speciesId: "${Species.Reflection.globalId("2")}"
                    }) {
                        id
                    }
                }
            """.trimIndent()

            val createResult = client.executeGraphQLQuery(createCharacterQuery)

            val query = """
                mutation {
                    updateCharacterName(id: "${createResult.path("data").path("createCharacter").path("id").asText()}", name: "Chewbacca Updated") {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            updatedCharacter shouldNotBe null
            updatedCharacter.path("name").asText() shouldBe "Chewbacca Updated"
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

            val response = client.executeGraphQLQuery(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            updatedCharacter.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 9999 not found"
        }

        @Test
        fun `should resolve addCharacterToFilm mutation`() {
            // First, create a new character to add to the film
            `should resolve createCharacter mutation`()

            val query = """
                mutation {
                    addCharacterToFilm(input: {
                        filmId: "${Film.Reflection.globalId("1")}"
                        characterId: "${Character.Reflection.globalId("6")}"
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

            val response = client.executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            updatedFilm shouldNotBe null
            updatedFilm.path("film").path("title").asText() shouldBe "A New Hope"
            updatedFilm.path("character").path("name").asText() shouldBe "Chewbacca"
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if filmId is invalid`() {
            val query = """
                mutation {
                    addCharacterToFilm(input: {
                        filmId: "${Film.Reflection.globalId("9999")}"
                        characterId: "${Character.Reflection.globalId("1")}"
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

            val response = client.executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            updatedFilm.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Film with ID 9999 not found"
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if characterId is invalid`() {
            val query = """
                mutation {
                    addCharacterToFilm(input: {
                        filmId: "${Film.Reflection.globalId("1")}"
                        characterId: "${Character.Reflection.globalId("9999")}"
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
            val response = client.executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            updatedFilm.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 9999 not found"
        }

        @Test
        fun `should fail to resolve addCharacterToFilm if character is already in film`() {
            val query = """
                mutation {
                    addCharacterToFilm(input: {
                        filmId: "${Film.Reflection.globalId("1")}"
                        characterId: "${Character.Reflection.globalId("1")}"
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
            val response = client.executeGraphQLQuery(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            updatedFilm.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 1 is already in film with ID 1"
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
                        homeworldId: "${Character.Reflection.globalId("5")}"
                        speciesId:  "${Species.Reflection.globalId("2")}"
                    }) {
                        id
                    }
                }
            """.trimIndent()
            val createResult = client.executeGraphQLQuery(createCharacterQuery)

            val query = """
    mutation {
        deleteCharacter(id: "${createResult.path("data").path("createCharacter").path("id").asText()}")
        }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val deleteResult = response.path("data").path("deleteCharacter").asBoolean()
            deleteResult shouldBe true
        }

        @Test
        fun `should fail to resolve deleteCharacter if id is invalid`() {
            val query = """
    mutation {
        deleteCharacter(id: "${Character.Reflection.globalId("9999")}")
    }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val deleteResult = response.path("data").path("deleteCharacter")
            deleteResult.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 9999 not found"
        }
    }
}
