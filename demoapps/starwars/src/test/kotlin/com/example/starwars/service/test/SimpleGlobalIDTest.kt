package com.example.starwars.service.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions
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
 * Tests for verifying GlobalID functionality in the Star Wars GraphQL API.
 *
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimpleGlobalIDTest {
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
    fun `should return encoded GlobalID for character query`() {
        val encodedCharacterId = Character.Reflection.globalId("1")
        val query = """
            query {
              node(id: "$encodedCharacterId") {
                ... on Character {
                  id
                  name
                }
              }
            }
        """.trimIndent()

        val result = executeGraphQLQuery(query)

        // Verify the response structure
        val data = result.get("data")
        Assertions.assertNotNull(data, "Data should not be null")

        val character = data.get("node")
        Assertions.assertNotNull(character, "Character should not be null")

        // Verify the GlobalID is encoded properly using GlobalIDImpl
        val globalId = character.get("id").asText()
        val expectedGlobalId = Character.Reflection.globalId("1")
        Assertions.assertEquals(expectedGlobalId, globalId, "GlobalID should match expected encoded value")

        // Verify the name is correct
        val name = character.get("name").asText()
        Assertions.assertEquals("Luke Skywalker", name, "Name should match expected value")
    }
}
