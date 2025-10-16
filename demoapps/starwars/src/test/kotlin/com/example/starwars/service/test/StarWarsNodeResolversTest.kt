package com.example.starwars.service.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import viaduct.api.grts.Planet
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle

/**
 * Tests for verifying Node and Nodes query functionality in the Star Wars GraphQL API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StarWarsNodeResolversTest {
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

    @Test
    fun `node query resolves Character automatically`() {
        val characterGlobalId = Character.Reflection.globalId("1")
        val query = """
            query TestCharacterNode {
                node(id: "$characterGlobalId") {
                    ... on Character {
                        id
                        name
                        birthYear
                        eyeColor
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val character = result.get("data").get("node")
        assertNotNull(character, "Character should be found")
        assertEquals("Luke Skywalker", character.get("name").asText())
        assertEquals("19BBY", character.get("birthYear").asText())
        assertEquals("blue", character.get("eyeColor").asText())

        // The id field should contain the GlobalID
        val returnedId = character.get("id").asText()
        assertNotNull(returnedId, "Character ID should be present")
        assertTrue(returnedId.isNotEmpty(), "Character ID should not be empty")
    }

    @Test
    fun `node query resolves Film automatically`() {
        val filmGlobalId = Film.Reflection.globalId("1")
        val query = """
            query TestFilmNode {
                node(id: "$filmGlobalId") {
                    ... on Film {
                        id
                        title
                        episodeID
                        director
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val film = result.get("data").get("node")
        assertNotNull(film, "Film should be found")
        assertEquals("A New Hope", film.get("title").asText())
        assertEquals(4, film.get("episodeID").asInt())
        assertEquals("George Lucas", film.get("director").asText())

        // The id field should contain the GlobalID
        val returnedId = film.get("id").asText()
        assertNotNull(returnedId, "Film ID should be present")
        assertTrue(returnedId.isNotEmpty(), "Film ID should not be empty")
    }

    @Test
    fun `node query resolves Planet automatically`() {
        val planetGlobalId = Planet.Reflection.globalId("1")
        val query = """
            query TestPlanetNode {
                node(id: "$planetGlobalId") {
                    ... on Planet {
                        id
                        name
                        diameter
                        rotationPeriod
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val planet = result.get("data").get("node")
        assertNotNull(planet, "Planet should be found")
        assertEquals("Tatooine", planet.get("name").asText())
        assertEquals(10465, planet.get("diameter").asInt())
        assertEquals(23, planet.get("rotationPeriod").asInt())

        // The id field should contain the GlobalID
        val returnedId = planet.get("id").asText()
        assertNotNull(returnedId, "Planet ID should be present")
        assertTrue(returnedId.isNotEmpty(), "Planet ID should not be empty")
    }

    @Test
    fun `node query resolves Species automatically`() {
        val speciesGlobalId = Species.Reflection.globalId("1")
        val query = """
            query TestSpeciesNode {
                node(id: "$speciesGlobalId") {
                    ... on Species {
                        id
                        name
                        classification
                        designation
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val species = result.get("data").get("node")
        assertNotNull(species, "Species should be found")
        assertEquals("Human", species.get("name").asText())
        assertEquals("mammal", species.get("classification").asText())
        assertEquals("sentient", species.get("designation").asText())

        // The id field should contain the GlobalID
        val returnedId = species.get("id").asText()
        assertNotNull(returnedId, "Species ID should be present")
        assertTrue(returnedId.isNotEmpty(), "Species ID should not be empty")
    }

    @Test
    fun `node query resolves Vehicle automatically`() {
        val vehicleGlobalId = Vehicle.Reflection.globalId("1")
        val query = """
            query TestVehicleNode {
                node(id: "$vehicleGlobalId") {
                    ... on Vehicle {
                        id
                        name
                        model
                        vehicleClass
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val vehicle = result.get("data").get("node")
        assertNotNull(vehicle, "Vehicle should be found")
        assertEquals("Speeder bike", vehicle.get("name").asText())
        assertEquals("74-Z speeder bike", vehicle.get("model").asText())
        assertEquals("speeder", vehicle.get("vehicleClass").asText())

        // The id field should contain the GlobalID
        val returnedId = vehicle.get("id").asText()
        assertNotNull(returnedId, "Vehicle ID should be present")
        assertTrue(returnedId.isNotEmpty(), "Vehicle ID should not be empty")
    }

    @Test
    fun `nodes query resolves multiple node types automatically`() {
        val characterGlobalId = Character.Reflection.globalId("1")
        val filmGlobalId = Film.Reflection.globalId("1")
        val planetGlobalId = Planet.Reflection.globalId("1")

        val query = """
            query TestMultipleNodes {
                nodes(ids: ["$characterGlobalId", "$filmGlobalId", "$planetGlobalId"]) {
                    ... on Character {
                        id
                        name
                    }
                    ... on Film {
                        id
                        title
                    }
                    ... on Planet {
                        id
                        name
                    }
                }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify no errors
        assertTrue(result.get("errors")?.isNull ?: true, "Query should not have errors")

        val nodes = result.get("data").get("nodes")
        assertNotNull(nodes, "Nodes should be found")
        assertEquals(3, nodes.size(), "Should return 3 nodes")

        // Verify each node has an ID
        for (node in nodes) {
            val id = node.get("id")?.asText()
            assertNotNull(id, "Each node should have an ID")
            assertTrue(id!!.isNotEmpty(), "Each node ID should not be empty")
        }

        // Verify we got different types (at least one should have name, one should have title)
        val hasCharacterName = nodes.any { it.has("name") && it.get("name").asText() == "Luke Skywalker" }
        val hasFilmTitle = nodes.any { it.has("title") && it.get("title").asText() == "A New Hope" }
        val hasPlanetName = nodes.any { it.has("name") && it.get("name").asText() == "Tatooine" }

        assertTrue(hasCharacterName, "Should include Character with name Luke Skywalker")
        assertTrue(hasFilmTitle, "Should include Film with title A New Hope")
        assertTrue(hasPlanetName, "Should include Planet with name Tatooine")
    }
}
