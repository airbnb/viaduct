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
import viaduct.api.grts.Species

/**
 * Tests for verifying behavior of fields protected by the "extras" scope.
 *
 * These tests ensure that fields requiring the "extras" scope are only accessible
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtrasScopeTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    // tag::header_execution_example[22] Example of executing a GraphQL query with custom headers
    private fun executeGraphQLQuery(
        query: String,
        headers: Map<String, String> = emptyMap()
    ): JsonNode {
        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.APPLICATION_JSON

        // Add any custom headers (for scopes, etc.)
        headers.forEach { (key, value) ->
            httpHeaders.set(key, value)
        }

        val request = mapOf("query" to query)
        val entity = HttpEntity(request, httpHeaders)

        val response = restTemplate.postForEntity(
            "http://localhost:$port/graphql",
            entity,
            String::class.java
        )

        return objectMapper.readTree(response.body)
    }

    @Test
    fun `test extras scope data is available when scope is present`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        name
                        culturalNotes
                        rarityLevel
                        specialAbilities
                        technologicalLevel
                    }
                }
            }
        """.trimIndent()

        // Add extras scope header
        val headers = mapOf("X-Viaduct-Scopes" to "extras")
        val response = executeGraphQLQuery(query, headers)

        // Verify the query executed successfully
        assertTrue(response.get("errors")?.isNull ?: true, "Query should execute without errors when extras scope is present")

        val species = response.get("data").get("node")
        assertNotNull(species, "Species should be found")

        // Verify the extras data is available
        assertNotNull(species.get("culturalNotes"), "Cultural notes should be available with extras scope")
        assertNotNull(species.get("rarityLevel"), "Rarity level should be available with extras scope")
        assertNotNull(species.get("specialAbilities"), "Special abilities should be available with extras scope")
        assertNotNull(species.get("technologicalLevel"), "Technological level should be available with extras scope")

        // Verify the actual data values are returned
        assertTrue(species.get("culturalNotes").asText().contains("Diverse species with strong adaptability"), "Cultural notes data should be returned")
        assertEquals("Common", species.get("rarityLevel").asText(), "Rarity level data should be returned")
        assertTrue(species.get("specialAbilities").toString().contains("Force sensitivity"), "Special abilities data should be returned")
        assertEquals("Advanced", species.get("technologicalLevel").asText(), "Technological level data should be returned")
    }

    @Test
    fun `test extras scope data is unavailable when scope is not present`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        name
                        culturalNotes
                        rarityLevel
                        specialAbilities
                        technologicalLevel
                    }
                }
            }
        """.trimIndent()

        // No scopes provided - should not include extras scope
        val response = executeGraphQLQuery(query)

        // Verify the query has errors due to restricted fields
        assertTrue(response.has("errors") && !response.get("errors").isNull, "Query should have errors when extras scope is not present")

        // Verify that the error mentions the restricted fields or field access
        val errorMessage = response.get("errors").toString().lowercase()
        assertTrue(
            errorMessage.contains("culturalnotes") || errorMessage.contains("cultural") ||
                errorMessage.contains("rarity") || errorMessage.contains("special") ||
                errorMessage.contains("technological") || errorMessage.contains("scope") ||
                errorMessage.contains("field") || errorMessage.contains("access"),
            "Error message should indicate that extras fields are not accessible without proper scope: $errorMessage"
        )
    }

    @Test
    fun `test species query works without extras fields when scope is not present`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        name
                        classification
                        designation
                        averageHeight
                        averageLifespan
                        eyeColors
                        hairColors
                        language
                    }
                }
            }
        """.trimIndent()

        // No scopes provided
        val response = executeGraphQLQuery(query)

        // Verify the query executed successfully for non-scoped fields
        assertTrue(response.get("errors")?.isNull ?: true, "Query should execute without errors for non-scoped fields")

        val species = response.get("data").get("node")
        assertNotNull(species, "Species should be found")

        // Verify basic species data is available
        assertNotNull(species.get("name"), "Name should be available without extras scope")
        assertEquals("Human", species.get("name").asText(), "Species name should be returned")
        assertNotNull(species.get("classification"), "Classification should be available without extras scope")
        assertEquals("mammal", species.get("classification").asText(), "Classification data should be returned")
    }
}
