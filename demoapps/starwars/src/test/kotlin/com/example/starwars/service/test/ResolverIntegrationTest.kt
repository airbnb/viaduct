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
// tag::resolver_base_test[10]
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
        fun `should resolve createCharacter mutation with admin access`() {
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)
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
        fun `should fail to resolve createCharacter mutation without admin access`() {
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
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val createdCharacter = response.path("data").path("createCharacter")
            createdCharacter.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "SecurityException: Insufficient permissions!"
        }

        @Test
        fun `should fail to resolve createCharacter mutation with invalid security header`() {
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
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQueryWithCustomAccess(query, "user")

            val createdCharacter = response.path("data").path("createCharacter")
            createdCharacter.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "SecurityException: Insufficient permissions!"
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Invalid GlobalID"
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Invalid GlobalID"
        }

        @Test
        fun `should resolve updateCharacterName mutation with admin access`() {
            // First, create a new character to update
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

            val createResult = client.executeGraphQLQueryWithAdminAccess(createCharacterQuery)

            val query = """
                mutation {
                    updateCharacterName(id: "${createResult.path("data").path("createCharacter").path("id").asText()}", name: "Chewbacca Updated") {
                        id
                        name
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            updatedCharacter shouldNotBe null
            updatedCharacter.path("name").asText() shouldBe "Chewbacca Updated"
        }

        @Test
        fun `should fail to resolve updateCharacterName mutation without admin access`() {
            val query = """
                mutation {
                    updateCharacterName(id: "${Character.Reflection.globalId("1")}", name: "Updated Name") {
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
            errorMessage shouldContain "SecurityException: Insufficient permissions!"
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val updatedCharacter = response.path("data").path("updateCharacterName")
            updatedCharacter.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 9999 not found"
        }

        @Test
        fun `should resolve addCharacterToFilm mutation with admin access`() {
            // First, create a new character to add to the film
            val createCharacterQuery = """
                mutation {
                    createCharacter(input: {
                        name: "Test Character for Film"
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
                    }
                }
            """.trimIndent()

            val createResult = client.executeGraphQLQueryWithAdminAccess(createCharacterQuery)
            val characterId = createResult.path("data").path("createCharacter").path("id").asText()

            val query = """
                mutation {
                    addCharacterToFilm(input: {
                        filmId: "${Film.Reflection.globalId("1")}"
                        characterId: "$characterId"
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val result = response.path("data").path("addCharacterToFilm")
            result shouldNotBe null
            result.path("film").path("title").asText() shouldBe "A New Hope"
            result.path("character").path("name").asText() shouldBe "Test Character for Film"
        }

        @Test
        fun `should fail to resolve addCharacterToFilm mutation without admin access`() {
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

            val result = response.path("data").path("addCharacterToFilm")
            result.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "SecurityException: Insufficient permissions!"
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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

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

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val updatedFilm = response.path("data").path("addCharacterToFilm")
            updatedFilm.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 1 is already in film with ID 1"
        }

        @Test
        fun `should resolve deleteCharacter mutation with admin access`() {
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

            val createResult = client.executeGraphQLQueryWithAdminAccess(createCharacterQuery)

            val query = """
                mutation {
                    deleteCharacter(id: "${createResult.path("data").path("createCharacter").path("id").asText()}")
                }
            """.trimIndent()

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val deleteResult = response.path("data").path("deleteCharacter").asBoolean()
            deleteResult shouldBe true
        }

        @Test
        fun `should fail to resolve deleteCharacter mutation without admin access`() {
            val query = """
                mutation {
                    deleteCharacter(id: "${Character.Reflection.globalId("1")}")
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)

            val deleteResult = response.path("data").path("deleteCharacter")
            deleteResult.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "SecurityException: Insufficient permissions!"
        }

        @Test
        fun `should fail to resolve deleteCharacter if id is invalid`() {
            val query = """
                mutation {
                    deleteCharacter(id: "${Character.Reflection.globalId("9999")}")
                }
            """.trimIndent()

            val response = client.executeGraphQLQueryWithAdminAccess(query)

            val deleteResult = response.path("data").path("deleteCharacter")
            deleteResult.isMissingNode shouldBe true
            val errors = response.path("errors")
            errors shouldNotBe null
            (errors.isArray && errors.size() == 1) shouldBe true
            val errorMessage = errors[0].path("message").asText()
            errorMessage shouldContain "Character with ID 9999 not found"
        }
    }

    @Nested
    inner class ConnectionResolvers {
        @Test
        fun `should resolve allCharactersConnection with edges and pageInfo`() {
            val query = """
                query {
                    allCharactersConnection(first: 3) {
                        edges {
                            cursor
                            node {
                                id
                                name
                            }
                        }
                        pageInfo {
                            hasNextPage
                            hasPreviousPage
                            startCursor
                            endCursor
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allCharactersConnection")

            connection shouldNotBe null
            val edges = connection.path("edges")
            (edges.size() > 0) shouldBe true
            (edges.size() <= 3) shouldBe true

            val firstEdge = edges[0]
            firstEdge.path("cursor").asText().shouldNotBeEmpty()
            firstEdge.path("node").path("id").asText().shouldNotBeEmpty()
            firstEdge.path("node").path("name").asText().shouldNotBeEmpty()

            val pageInfo = connection.path("pageInfo")
            // 3 items fit on first page of a dataset with more characters, so hasNextPage should be true
            pageInfo.path("hasNextPage").asBoolean() shouldBe true
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe false
            pageInfo.path("startCursor").asText().shouldNotBeEmpty()
            pageInfo.path("endCursor").asText().shouldNotBeEmpty()
        }

        @Test
        fun `should resolve allCharactersConnection forward pagination with after cursor`() {
            // Fetch first page to get the endCursor
            val firstPageQuery = """
                query {
                    allCharactersConnection(first: 2) {
                        edges { cursor node { id name } }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            """.trimIndent()

            val firstPageResponse = client.executeGraphQLQuery(firstPageQuery)
            val firstPageConnection = firstPageResponse.path("data").path("allCharactersConnection")
            val endCursor = firstPageConnection.path("pageInfo").path("endCursor").asText()
            val firstPageNames = (0 until firstPageConnection.path("edges").size())
                .map { firstPageConnection.path("edges")[it].path("node").path("name").asText() }

            // Use endCursor to fetch next page
            val secondPageQuery = """
                query {
                    allCharactersConnection(first: 2, after: "$endCursor") {
                        edges { cursor node { id name } }
                        pageInfo { hasPreviousPage }
                    }
                }
            """.trimIndent()

            val secondPageResponse = client.executeGraphQLQuery(secondPageQuery)
            val secondPageConnection = secondPageResponse.path("data").path("allCharactersConnection")
            val secondPageEdges = secondPageConnection.path("edges")

            // Second page should have items
            (secondPageEdges.size() > 0) shouldBe true
            // Second page should show previous page exists
            secondPageConnection.path("pageInfo").path("hasPreviousPage").asBoolean() shouldBe true

            // Names on second page should differ from first page
            val secondPageNames = (0 until secondPageEdges.size())
                .map { secondPageEdges[it].path("node").path("name").asText() }
            (firstPageNames.intersect(secondPageNames.toSet()).isEmpty()) shouldBe true
        }

        @Test
        fun `should resolve allCharactersConnection with no arguments uses default page size`() {
            val query = """
                query {
                    allCharactersConnection {
                        edges { cursor node { id } }
                        pageInfo { hasNextPage hasPreviousPage }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allCharactersConnection")

            (connection.path("edges").size() > 0) shouldBe true
        }

        @Test
        fun `should resolve allFilmsConnection with edges pageInfo and totalCount`() {
            val query = """
                query {
                    allFilmsConnection(first: 2) {
                        totalCount
                        edges {
                            cursor
                            node {
                                id
                                title
                                episodeID
                            }
                        }
                        pageInfo {
                            hasNextPage
                            hasPreviousPage
                            startCursor
                            endCursor
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allFilmsConnection")

            connection shouldNotBe null

            // totalCount reflects the full dataset
            val totalCount = connection.path("totalCount").asInt()
            (totalCount > 0) shouldBe true

            val edges = connection.path("edges")
            (edges.size() > 0) shouldBe true
            (edges.size() <= 2) shouldBe true

            val firstEdge = edges[0]
            firstEdge.path("cursor").asText().shouldNotBeEmpty()
            firstEdge.path("node").path("id").asText().shouldNotBeEmpty()
            firstEdge.path("node").path("title").asText().shouldNotBeEmpty()

            val pageInfo = connection.path("pageInfo")
            pageInfo.path("startCursor").asText().shouldNotBeEmpty()
            pageInfo.path("endCursor").asText().shouldNotBeEmpty()
            // first page with 2 items: hasPreviousPage is false
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe false
        }

        @Test
        fun `should resolve allFilmsConnection totalCount matches full film count`() {
            // Fetch the connection without pagination to get total
            val connectionQuery = """
                query {
                    allFilmsConnection {
                        totalCount
                        edges { node { id } }
                    }
                }
            """.trimIndent()

            val listQuery = """
                query {
                    allFilms(limit: 100) { id }
                }
            """.trimIndent()

            val connectionResponse = client.executeGraphQLQuery(connectionQuery)
            val listResponse = client.executeGraphQLQuery(listQuery)

            val totalCount = connectionResponse.path("data").path("allFilmsConnection").path("totalCount").asInt()
            val listCount = listResponse.path("data").path("allFilms").size()

            totalCount shouldBe listCount
        }

        // ── Backward pagination (allPlanetsConnection, fromList) ────────────────

        @Test
        fun `should resolve allPlanetsConnection last N items using backward pagination`() {
            val query = """
                query {
                    allPlanetsConnection(last: 3) {
                        edges {
                            cursor
                            node { id name }
                        }
                        pageInfo {
                            hasPreviousPage
                            hasNextPage
                            startCursor
                            endCursor
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allPlanetsConnection")

            connection shouldNotBe null
            val edges = connection.path("edges")
            edges.size() shouldBe 3

            // Cursors must be present on every edge
            edges.forEach { edge ->
                edge.path("cursor").asText().shouldNotBeEmpty()
                edge.path("node").path("id").asText().shouldNotBeEmpty()
            }

            val pageInfo = connection.path("pageInfo")
            // Last 3 items — there are items before them
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe true
            // Last 3 items — nothing after them in the forward direction
            pageInfo.path("hasNextPage").asBoolean() shouldBe false
            pageInfo.path("startCursor").asText().shouldNotBeEmpty()
            pageInfo.path("endCursor").asText().shouldNotBeEmpty()
        }

        @Test
        fun `should resolve allPlanetsConnection backward pagination with before cursor`() {
            // Fetch the last 3 to get a startCursor to page backward from
            val lastPageQuery = """
                query {
                    allPlanetsConnection(last: 3) {
                        edges { cursor node { id name } }
                        pageInfo { startCursor }
                    }
                }
            """.trimIndent()

            val lastPageResponse = client.executeGraphQLQuery(lastPageQuery)
            val lastPageConnection = lastPageResponse.path("data").path("allPlanetsConnection")
            val startCursor = lastPageConnection.path("pageInfo").path("startCursor").asText()
            val lastPageNames = (0 until lastPageConnection.path("edges").size())
                .map { lastPageConnection.path("edges")[it].path("node").path("name").asText() }

            // Fetch the 3 items before the startCursor
            val prevPageQuery = """
                query {
                    allPlanetsConnection(last: 3, before: "$startCursor") {
                        edges { cursor node { id name } }
                        pageInfo { hasPreviousPage hasNextPage }
                    }
                }
            """.trimIndent()

            val prevPageResponse = client.executeGraphQLQuery(prevPageQuery)
            val prevPageConnection = prevPageResponse.path("data").path("allPlanetsConnection")
            val prevPageEdges = prevPageConnection.path("edges")

            (prevPageEdges.size() > 0) shouldBe true

            // Items on the previous page must not overlap with the last page
            val prevPageNames = (0 until prevPageEdges.size())
                .map { prevPageEdges[it].path("node").path("name").asText() }
            (lastPageNames.intersect(prevPageNames.toSet()).isEmpty()) shouldBe true
        }

        // ── Forward pagination + fromSlice (allStarshipsConnection) ────────────

        @Test
        fun `should resolve allStarshipsConnection with edges and pageInfo using fromSlice`() {
            val query = """
                query {
                    allStarshipsConnection(first: 1) {
                        edges {
                            cursor
                            node { id name model }
                        }
                        pageInfo {
                            hasNextPage
                            hasPreviousPage
                            startCursor
                            endCursor
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allStarshipsConnection")

            connection shouldNotBe null
            val edges = connection.path("edges")
            edges.size() shouldBe 1

            val pageInfo = connection.path("pageInfo")
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe false
            pageInfo.path("hasNextPage").asBoolean() shouldBe true
            pageInfo.path("startCursor").asText().shouldNotBeEmpty()
            pageInfo.path("endCursor").asText().shouldNotBeEmpty()
        }

        @Test
        fun `should resolve allStarshipsConnection forward pagination with after cursor`() {
            val firstPageQuery = """
                query {
                    allStarshipsConnection(first: 1) {
                        edges { cursor node { id name } }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            """.trimIndent()

            val firstPageResponse = client.executeGraphQLQuery(firstPageQuery)
            val firstPageConnection = firstPageResponse.path("data").path("allStarshipsConnection")
            val endCursor = firstPageConnection.path("pageInfo").path("endCursor").asText()
            val firstPageNames = (0 until firstPageConnection.path("edges").size())
                .map { firstPageConnection.path("edges")[it].path("node").path("name").asText() }

            val secondPageQuery = """
                query {
                    allStarshipsConnection(first: 1, after: "$endCursor") {
                        edges { cursor node { id name } }
                        pageInfo { hasPreviousPage }
                    }
                }
            """.trimIndent()

            val secondPageResponse = client.executeGraphQLQuery(secondPageQuery)
            val secondPageConnection = secondPageResponse.path("data").path("allStarshipsConnection")
            val secondPageEdges = secondPageConnection.path("edges")

            (secondPageEdges.size() > 0) shouldBe true
            // After using an after cursor the second page knows there's a previous page
            secondPageConnection.path("pageInfo").path("hasPreviousPage").asBoolean() shouldBe true

            val secondPageNames = (0 until secondPageEdges.size())
                .map { secondPageEdges[it].path("node").path("name").asText() }
            (firstPageNames.intersect(secondPageNames.toSet()).isEmpty()) shouldBe true
        }

        // ── Multidirectional + fromEdges (allSpeciesConnection) ─────────────────

        @Test
        fun `should resolve allSpeciesConnection forward pagination using fromEdges`() {
            val query = """
                query {
                    allSpeciesConnection(first: 1) {
                        edges {
                            cursor
                            node { id name classification }
                        }
                        pageInfo {
                            hasNextPage
                            hasPreviousPage
                            startCursor
                            endCursor
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allSpeciesConnection")

            connection shouldNotBe null
            val edges = connection.path("edges")
            edges.size() shouldBe 1

            // fromEdges: verify cursors were manually encoded
            edges.forEach { edge -> edge.path("cursor").asText().shouldNotBeEmpty() }

            val pageInfo = connection.path("pageInfo")
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe false
            pageInfo.path("hasNextPage").asBoolean() shouldBe true
        }

        @Test
        fun `should resolve allSpeciesConnection backward last N using fromEdges`() {
            val query = """
                query {
                    allSpeciesConnection(last: 1) {
                        edges {
                            cursor
                            node { id name }
                        }
                        pageInfo {
                            hasPreviousPage
                            hasNextPage
                        }
                    }
                }
            """.trimIndent()

            val response = client.executeGraphQLQuery(query)
            val connection = response.path("data").path("allSpeciesConnection")

            val edges = connection.path("edges")
            edges.size() shouldBe 1

            val pageInfo = connection.path("pageInfo")
            // Last 1 species: items exist before it (effectiveOffset = totalSize - 1 = 1 > 0)
            pageInfo.path("hasPreviousPage").asBoolean() shouldBe true
            // Last 1 species: nothing after in forward direction
            pageInfo.path("hasNextPage").asBoolean() shouldBe false
        }

        @Test
        fun `should resolve allSpeciesConnection backward with before cursor using fromEdges`() {
            // Fetch last 1 to get a startCursor (last species, at offset 1 in a 2-item repo)
            val lastPageQuery = """
                query {
                    allSpeciesConnection(last: 1) {
                        edges { cursor node { id name } }
                        pageInfo { startCursor }
                    }
                }
            """.trimIndent()

            val lastPageResponse = client.executeGraphQLQuery(lastPageQuery)
            val lastPageConnection = lastPageResponse.path("data").path("allSpeciesConnection")
            val startCursor = lastPageConnection.path("pageInfo").path("startCursor").asText()
            val lastPageNames = (0 until lastPageConnection.path("edges").size())
                .map { lastPageConnection.path("edges")[it].path("node").path("name").asText() }

            // Fetch 1 item before the startCursor (the species before offset 1, i.e. offset 0)
            val prevPageQuery = """
                query {
                    allSpeciesConnection(last: 1, before: "$startCursor") {
                        edges { cursor node { id name } }
                        pageInfo { hasPreviousPage }
                    }
                }
            """.trimIndent()

            val prevPageResponse = client.executeGraphQLQuery(prevPageQuery)
            val prevPageConnection = prevPageResponse.path("data").path("allSpeciesConnection")
            val prevPageEdges = prevPageConnection.path("edges")

            (prevPageEdges.size() > 0) shouldBe true
            val prevPageNames = (0 until prevPageEdges.size())
                .map { prevPageEdges[it].path("node").path("name").asText() }
            // Items before last 2 must not overlap with the last 2
            (lastPageNames.intersect(prevPageNames.toSet()).isEmpty()) shouldBe true
        }

        @Test
        fun `should resolve allSpeciesConnection forward then backward returns different items`() {
            // Forward: first 1 species (offset 0)
            val forwardQuery = """
                query {
                    allSpeciesConnection(first: 1) {
                        edges { node { name } }
                    }
                }
            """.trimIndent()

            // Backward: last 1 species (offset 1 in a 2-item repo)
            val backwardQuery = """
                query {
                    allSpeciesConnection(last: 1) {
                        edges { node { name } }
                    }
                }
            """.trimIndent()

            val forwardResponse = client.executeGraphQLQuery(forwardQuery)
            val backwardResponse = client.executeGraphQLQuery(backwardQuery)

            val forwardEdges = forwardResponse.path("data").path("allSpeciesConnection").path("edges")
            val backwardEdges = backwardResponse.path("data").path("allSpeciesConnection").path("edges")

            val forwardNames = (0 until forwardEdges.size()).map { forwardEdges[it].path("node").path("name").asText() }.toSet()
            val backwardNames = (0 until backwardEdges.size()).map { backwardEdges[it].path("node").path("name").asText() }.toSet()

            // First 2 and last 2 of the species list should be different
            (forwardNames.intersect(backwardNames).isEmpty()) shouldBe true
        }
    }
}
