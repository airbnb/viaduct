package com.example.starwars.service.test

import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.api.grts.Character

/**
 * Tests for verifying GlobalID functionality in the Star Wars GraphQL API.
 */
@MicronautTest(startApplication = true)
class SimpleGlobalIDTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

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

        val result = client.executeGraphQLQuery(query)

        // Verify the response structure
        val data = result.get("data")
        assertNotNull(data)

        val character = data.get("node")
        assertNotNull(character)

        // Verify the GlobalID is encoded properly using GlobalIDImpl
        val globalId = character.get("id").asText()
        val expectedGlobalId = Character.Reflection.globalId("1")
        assertEquals(expectedGlobalId, globalId)

        // Verify the name is correct
        val name = character.get("name").asText()
        assertEquals("Luke Skywalker", name)
    }
}
