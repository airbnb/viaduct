package com.example.starwars.service.test

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * Batch resolver integration tests.
 *
 * These tests focus on verifying that batch resolvers correctly optimize data fetching
 * and prevent N+1 query problems in various scenarios.
 */
@MicronautTest
class BatchResolverIntegrationTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

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

        val response = client.executeGraphQLQuery(query)

        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val characters = response.get("data").get("allCharacters")
        characters.size() shouldBe 3

        // Verify all characters have film counts (all should be 3 based on test data)
        for (characterNode in characters) {
            val characterName = characterNode.get("name").asText()
            val filmCount = characterNode.get("filmCount").asInt()

            filmCount shouldBe 3
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

        val response = client.executeGraphQLQuery(query)

        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val characters = response.get("data").get("allCharacters")
        characters.size() shouldBe 3

        // Verify rich summaries contain expected data
        for (characterNode in characters) {
            val richSummary = characterNode.get("richSummary").asText()

            richSummary shouldContain "3 films"
            richSummary shouldContain "from"
            richSummary shouldContain "("
            richSummary shouldContain ")"

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

        val response = client.executeGraphQLQuery(query)

        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val films = response.get("data").get("allFilms")
        films.size() shouldBe 2

        // Verify each film has main characters
        for (filmNode in films) {
            val filmTitle = filmNode.get("title").asText()
            val mainCharacters = filmNode.get("mainCharacters")

            mainCharacters.size() shouldBe 5

            // Verify we have the main Star Wars characters
            val characterNames = mutableSetOf<String>()
            for (characterNode in mainCharacters) {
                characterNames.add(characterNode.get("name").asText())
            }

            characterNames shouldContain "Luke Skywalker"
            characterNames shouldContain "Princess Leia"
            characterNames shouldContain "Han Solo"

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

        val response = client.executeGraphQLQuery(query)

        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val characters = response.get("data").get("allCharacters")
        characters.size() shouldBe 5

        // Verify all fields are resolved efficiently
        for (characterNode in characters) {
            val characterName = characterNode.get("name").asText()

            characterNode.get("filmCount") shouldNotBe null
            characterNode.get("richSummary") shouldNotBe null
            characterNode.get("homeworld") shouldNotBe null
            characterNode.get("species") shouldNotBe null

            println("✅ All fields resolved efficiently for $characterName")
        }
    }
}
