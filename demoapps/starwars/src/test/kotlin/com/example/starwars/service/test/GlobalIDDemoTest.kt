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

/**
 * Demonstration test for GlobalID tests in the Star Wars GraphQL API.
 *
 * This test demonstrates:
 * 1. How Character objects implement the Node interface with encoded GlobalID
 * 2. How to query using string IDs and receive encoded GlobalIDs in response
 * 3. How GlobalID provides a unique, typed identifier for objects in the graph
 * 4. The correct pattern: query with string ID, get encoded GlobalID back
 */
@MicronautTest
class GlobalIDDemoTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `should demonstrate GlobalID with Node interface`() {
        val encodedCharacterId = Character.Reflection.globalId("1")
        val query = """
            query GetCharacterWithGlobalId {
              node(id: "$encodedCharacterId") {
                ... on Character {
                  # With Node interface, id field returns encoded GlobalID
                  id

                  # Regular character fields
                  name
                  birthYear
                  eyeColor
                }
              }
            }
        """.trimIndent()

        val result = client.executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        hasErrors shouldBe false

        val character = result.get("data").get("node")
        character shouldNotBe null

        // Verify basic character data
        character.get("name").asText() shouldBe "Luke Skywalker"
        character.get("birthYear").asText() shouldBe "19BBY"
        character.get("eyeColor").asText() shouldBe "blue"

        // Verify GlobalID (encoded format from Node interface)
        val characterId = character.get("id").asText()
        characterId shouldNotBe null
        characterId.shouldNotBeEmpty()
        // GlobalID is encoded, so we just verify it's a valid non-empty string
    }

    @Test
    fun `should demonstrate GlobalID encoding with Node interface`() {
        // Query person using encoded GlobalID, get encoded GlobalID back
        val encodedCharacterId = Character.Reflection.globalId("2")
        val query = """
            query {
              node(id: "$encodedCharacterId") {
                ... on Character {
                  # Node interface id field returns encoded GlobalID
                  id
                  name
                }
              }
            }
        """.trimIndent()

        val result = client.executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        hasErrors shouldBe false

        val character = result.get("data").get("node")
        character shouldNotBe null

        // Verify person data
        character.get("name").asText() shouldBe "Princess Leia"

        // Verify encoded GlobalID
        val characterId = character.get("id").asText()
        characterId shouldNotBe null
        characterId.shouldNotBeEmpty()

        // The GlobalID is encoded, so we verify it's a valid non-empty string
        // In the Node interface pattern, the id field contains the encoded GlobalID
    }

    @Test
    fun `should show GlobalID consistency across multiple characters`() {
        val query = """
            query GetMultipleCharactersWithGlobalIds {
              allCharacters(limit: 3) {
                # Node interface id field returns encoded GlobalID for each character
                id
                name
              }
            }
        """.trimIndent()

        val result = client.executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        hasErrors shouldBe false

        val characters = result.get("data").get("allCharacters")

        // Verify we got multiple characters
        characters.size() shouldBe 3

        // Verify each person has a valid encoded GlobalID
        for (personNode in characters) {
            val characterId = personNode.get("id").asText()
            val name = personNode.get("name").asText()

            // Verify encoded GlobalID is present and non-empty for each person
            characterId shouldNotBe null
            characterId.shouldNotBeEmpty()

            // The GlobalID is encoded, so we just verify it's a valid non-empty string
            // Each person implementing Node interface gets their own encoded GlobalID
        }
    }
}
