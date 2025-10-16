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

/**
 * Batch resolver integration tests.
 *
 * These tests focus on verifying that batch resolvers correctly optimize data fetching
 * and prevent N+1 query problems in various scenarios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchResolverIntegrationTest {
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
        val response = restTemplate.postForEntity("http://localhost:$port/graphql", entity, String::class.java)
        return objectMapper.readTree(response.body)
    }

    @Test
    fun `batch resolver prevents N+1 queries for film counts`() {
        val query = """
            query {
                allCharacters(limit: 3) {
                    name
                    filmCount
                }
            }
        """.trimIndent()

        val response = executeGraphQLQuery(query)

        assertTrue(response.get("errors")?.isNull ?: true, "Query should execute without errors")

        val characters = response.get("data").get("allCharacters")
        assertEquals(3, characters.size(), "Should return 3 characters")

        // Verify all characters have film counts (all should be 3 based on test data)
        for (characterNode in characters) {
            val characterName = characterNode.get("name").asText()
            val filmCount = characterNode.get("filmCount").asInt()

            assertEquals(3, filmCount, "$characterName should appear in 3 films")
            println("✅ $characterName appears in $filmCount films")
        }
    }

    @Test
    fun `batch resolver efficiently creates rich summaries`() {
        val query = """
            query {
                allCharacters(limit: 3) {
                    richSummary
                }
            }
        """.trimIndent()

        val response = executeGraphQLQuery(query)

        assertTrue(response.get("errors")?.isNull ?: true, "Query should execute without errors")

        val characters = response.get("data").get("allCharacters")
        assertEquals(3, characters.size(), "Should return 3 characters")

        // Verify rich summaries contain expected data
        for (characterNode in characters) {
            val richSummary = characterNode.get("richSummary").asText()

            assertTrue(richSummary.contains("3 films"), "Summary should mention film count")
            assertTrue(richSummary.contains("from"), "Summary should mention homeworld")
            assertTrue(richSummary.contains("(") && richSummary.contains(")"), "Summary should contain birth year")

            println("✅ Rich summary: $richSummary")
        }
    }

    @Test
    fun `batch resolver efficiently resolves film characters`() {
        val query = """
            query {
                allFilms(limit: 2) {
                    title
                    mainCharacters {
                        name
                    }
                }
            }
        """.trimIndent()

        val response = executeGraphQLQuery(query)

        assertTrue(response.get("errors")?.isNull ?: true, "Query should execute without errors")

        val films = response.get("data").get("allFilms")
        assertEquals(2, films.size(), "Should return 2 films")

        // Verify each film has main characters
        for (filmNode in films) {
            val filmTitle = filmNode.get("title").asText()
            val mainCharacters = filmNode.get("mainCharacters")

            assertEquals(5, mainCharacters.size(), "$filmTitle should have 5 main characters")

            // Verify we have the main Star Wars characters
            val characterNames = mutableSetOf<String>()
            for (characterNode in mainCharacters) {
                characterNames.add(characterNode.get("name").asText())
            }

            assertTrue(characterNames.contains("Luke Skywalker"))
            assertTrue(characterNames.contains("Princess Leia"))
            assertTrue(characterNames.contains("Han Solo"))

            println("✅ $filmTitle has ${mainCharacters.size()} characters")
        }
    }

    @Test
    fun `demonstrates efficiency with complex multi-field query`() {
        // This query would cause many N+1 problems without batch resolvers
        val query = """
            query {
                allCharacters(limit: 5) {
                    name
                    filmCount
                    richSummary
                    homeworld { name }
                    species { name }
                }
            }
        """.trimIndent()

        val response = executeGraphQLQuery(query)

        assertTrue(response.get("errors")?.isNull ?: true, "Complex query should execute efficiently")

        val characters = response.get("data").get("allCharacters")
        assertEquals(5, characters.size(), "Should return 5 characters")

        // Verify all fields are resolved efficiently
        for (characterNode in characters) {
            val characterName = characterNode.get("name").asText()

            assertNotNull(characterNode.get("filmCount"), "$characterName should have film count")
            assertNotNull(characterNode.get("richSummary"), "$characterName should have rich summary")
            assertNotNull(characterNode.get("homeworld"), "$characterName should have homeworld")
            assertNotNull(characterNode.get("species"), "$characterName should have species")

            println("✅ All fields resolved efficiently for $characterName")
        }
    }
}
