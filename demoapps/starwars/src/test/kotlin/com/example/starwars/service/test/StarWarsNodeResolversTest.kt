package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character
import viaduct.api.grts.Film
import viaduct.api.grts.Planet
import viaduct.api.grts.Species
import viaduct.api.grts.Vehicle

/**
 * Tests for verifying Node and Nodes query functionality in the Star Wars GraphQL API.
 */
@MicronautTest
class StarWarsNodeResolversTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val character = result.get("data").get("node")
        character shouldNotBe null
        character.get("name").asText() shouldBe "Luke Skywalker"
        character.get("birthYear").asText() shouldBe "19BBY"
        character.get("eyeColor").asText() shouldBe "blue"

        // The id field should contain the GlobalID
        val returnedId = character.get("id").asText()
        returnedId shouldNotBe null
        returnedId.shouldNotBeEmpty()
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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val film = result.get("data").get("node")
        film shouldNotBe null
        film.get("title").asText() shouldBe "A New Hope"
        film.get("episodeID").asInt() shouldBe 4
        film.get("director").asText() shouldBe "George Lucas"

        // The id field should contain the GlobalID
        val returnedId = film.get("id").asText()
        returnedId shouldNotBe null
        returnedId.shouldNotBeEmpty()
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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val planet = result.get("data").get("node")
        planet shouldNotBe null
        planet.get("name").asText() shouldBe "Tatooine"
        planet.get("diameter").asInt() shouldBe 10465
        planet.get("rotationPeriod").asInt() shouldBe 23

        // The id field should contain the GlobalID
        val returnedId = planet.get("id").asText()
        returnedId shouldNotBe null
        returnedId.shouldNotBeEmpty()
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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val species = result.get("data").get("node")
        species shouldNotBe null
        species.get("name").asText() shouldBe "Human"
        species.get("classification").asText() shouldBe "mammal"
        species.get("designation").asText() shouldBe "sentient"

        // The id field should contain the GlobalID
        val returnedId = species.get("id").asText()
        returnedId shouldNotBe null
        returnedId.shouldNotBeEmpty()
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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val vehicle = result.get("data").get("node")
        vehicle shouldNotBe null
        vehicle.get("name").asText() shouldBe "Speeder bike"
        vehicle.get("model").asText() shouldBe "74-Z speeder bike"
        vehicle.get("vehicleClass").asText() shouldBe "speeder"

        // The id field should contain the GlobalID
        val returnedId = vehicle.get("id").asText()
        returnedId shouldNotBe null
        returnedId.shouldNotBeEmpty()
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

        val result = client.executeGraphQLQuery(query)

        // Verify no errors
        val errors = result.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val nodes = result.get("data").get("nodes")
        nodes shouldNotBe null
        nodes.size() shouldBe 3

        // Verify each node has an ID
        for (node in nodes) {
            val id = node.get("id")?.asText()
            id shouldNotBe null
            id!!.shouldNotBeEmpty()
        }

        // Verify we got different types (at least one should have name, one should have title)
        val hasCharacterName = nodes.any { it.has("name") && it.get("name").asText() == "Luke Skywalker" }
        val hasFilmTitle = nodes.any { it.has("title") && it.get("title").asText() == "A New Hope" }
        val hasPlanetName = nodes.any { it.has("name") && it.get("name").asText() == "Tatooine" }

        hasCharacterName shouldBe true
        hasFilmTitle shouldBe true
        hasPlanetName shouldBe true
    }
}
