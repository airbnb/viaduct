package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import viaduct.api.grts.Species

/**
 * Tests for verifying behavior of fields protected by the "extras" scope on Species type.
 */
@MicronautTest
class SpeciesScopeHttpTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `should resolve basic species fields without extras scope`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        id
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

        val response = client.executeGraphQLQuery(query)

        // Verify no errors occurred
        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val species = response.get("data").get("node")
        species shouldNotBe null

        // Verify basic species data is available
        species.get("name") shouldNotBe null
        species.get("name").asText() shouldBe "Human"
        species.get("classification") shouldNotBe null
        species.get("classification").asText() shouldBe "mammal"
    }

    @Test
    fun `should query extras fields when available`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        id
                        name
                        culturalNotes
                        rarityLevel
                        specialAbilities
                        technologicalLevel
                    }
                }
            }
        """.trimIndent()

        val response = client.executeGraphQLQuery(query, scopes = setOf("extras"))

        // This query should work since we're passing the extras scope
        val hasErrors = response.has("errors") && !response.get("errors").isNull
        hasErrors shouldBe false

        val species = response.get("data").get("node")
        species shouldNotBe null
        species.get("name") shouldNotBe null

        // Verify we get real backend data for extras fields
        species.get("culturalNotes")?.asText() shouldBe
            "Diverse species with strong adaptability and technological advancement"
        species.get("rarityLevel")?.asText() shouldBe "Common"
        species.get("specialAbilities")?.isArray shouldBe true
    }

    @Test
    fun `should query extras fields even without extras scope`() {
        val encodedSpeciesId = Species.Reflection.globalId("1")
        val query = """
            query {
                node(id: "$encodedSpeciesId") {
                    ... on Species {
                        id
                        name
                        culturalNotes
                        rarityLevel
                        specialAbilities
                        technologicalLevel
                    }
                }
            }
        """.trimIndent()

        val response = client.executeGraphQLQuery(query) // No extras scope provided

        // With proper scoping, querying extras fields without scope should result in errors
        val hasErrors = response.has("errors") && !response.get("errors").isNull
        hasErrors shouldBe true

        // Verify that the error mentions the restricted fields or field access
        val errorMessage = response.get("errors").toString().lowercase()
        val containsRelevantError = errorMessage.contains("culturalnotes") ||
            errorMessage.contains("cultural") ||
            errorMessage.contains("rarity") ||
            errorMessage.contains("special") ||
            errorMessage.contains("technological") ||
            errorMessage.contains("scope") ||
            errorMessage.contains("field") ||
            errorMessage.contains("access")

        containsRelevantError shouldBe true
    }
}
