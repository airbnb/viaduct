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

/**
 * Demonstration test for GlobalID tests in the Star Wars GraphQL API.
 *
 * This test demonstrates:
 * 1. How Character objects implement the Node interface with encoded GlobalID
 * 2. How to query using string IDs and receive encoded GlobalIDs in response
 * 3. How GlobalID provides a unique, typed identifier for objects in the graph
 * 4. The correct pattern: query with string ID, get encoded GlobalID back
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GlobalIDDemoTest {
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

        val result = objectMapper.readTree(response.body)

        // If there are errors, print them for debugging
        val errors = result.get("errors")
        if (errors != null && !errors.isNull && errors.isArray && errors.size() > 0) {
            println("GraphQL Errors:")
            for (i in 0 until errors.size()) {
                val error = errors.get(i)
                println("  Error $i: ${error.toPrettyString()}")
            }
        }

        return result
    }

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

        val result = executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        assertEquals(
            false,
            hasErrors,
            "GraphQL query should not return errors${if (hasErrors) ". Errors: ${errors!!.toPrettyString()}" else ""}"
        )

        val character = result.get("data").get("node")
        assertNotNull(character, "Character should be found")

        // Verify basic character data
        assertEquals("Luke Skywalker", character.get("name").asText())
        assertEquals("19BBY", character.get("birthYear").asText())
        assertEquals("blue", character.get("eyeColor").asText())

        // Verify GlobalID (encoded format from Node interface)
        val characterId = character.get("id").asText()
        assertNotNull(characterId, "GlobalID should be present")
        assertTrue(characterId.isNotEmpty(), "GlobalID should not be empty")
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

        val result = executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        assertEquals(
            false,
            hasErrors,
            "GraphQL query should not return errors${if (hasErrors) ". Errors: ${errors!!.toPrettyString()}" else ""}"
        )

        val character = result.get("data").get("node")
        assertNotNull(character, "Character should be found")

        // Verify person data
        assertEquals("Princess Leia", character.get("name").asText())

        // Verify encoded GlobalID
        val characterId = character.get("id").asText()
        assertNotNull(characterId, "GlobalID should be present")
        assertTrue(characterId.isNotEmpty(), "GlobalID should not be empty")

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

        val result = executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = result.get("errors")
        val hasErrors = errors != null && !errors.isNull && errors.isArray && errors.size() > 0
        assertEquals(
            false,
            hasErrors,
            "GraphQL query should not return errors${if (hasErrors) ". Errors: ${errors!!.toPrettyString()}" else ""}"
        )

        val characters = result.get("data").get("allCharacters")

        // Verify we got multiple characters
        assertEquals(3, characters.size(), "Should return 3 characters")

        // Verify each person has a valid encoded GlobalID
        for (personNode in characters) {
            val characterId = personNode.get("id").asText()
            val name = personNode.get("name").asText()

            // Verify encoded GlobalID is present and non-empty for each person
            assertNotNull(characterId, "GlobalID should be present for person: $name")
            assertTrue(characterId.isNotEmpty(), "GlobalID should not be empty for person: $name")

            // The GlobalID is encoded, so we just verify it's a valid non-empty string
            // Each person implementing Node interface gets their own encoded GlobalID
        }
    }
}
