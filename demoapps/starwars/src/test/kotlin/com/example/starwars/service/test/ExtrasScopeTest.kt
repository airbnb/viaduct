package com.example.starwars.service.test

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import viaduct.api.grts.Species

/**
 * Tests for verifying behavior of fields protected by the "extras" scope.
 *
 * These tests ensure that fields requiring the "extras" scope are only accessible
 */
@MicronautTest
class ExtrasScopeTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `introspection on each route describes that route's schema`() {
        val query = """{ __type(name: "Species") { fields { name } } }"""

        fun fieldNames(path: String) =
            client.executeGraphQLQuery(query, path = path)
                .get("data").get("__type").get("fields").map { it.get("name").asText() }

        fieldNames("/graphql") shouldNotContain "culturalNotes"
        fieldNames("/extras/graphql") shouldContain "culturalNotes"
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

        val response = client.executeGraphQLQuery(query, path = "/extras/graphql")

        // Verify the query executed successfully
        val errors = response.get("errors")
        (errors?.isNull ?: true) shouldBe true

        val species = response.get("data").get("node")
        species shouldNotBe null

        // Verify the extras data is available
        species.get("culturalNotes") shouldNotBe null
        species.get("rarityLevel") shouldNotBe null
        species.get("specialAbilities") shouldNotBe null
        species.get("technologicalLevel") shouldNotBe null

        // Verify the actual data values are returned
        species.get("culturalNotes").asText() shouldContain "Diverse species with strong adaptability"
        species.get("rarityLevel").asText() shouldBe "Common"
        species.get("specialAbilities").toString() shouldContain "Force sensitivity"
        species.get("technologicalLevel").asText() shouldBe "Advanced"
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

        val response = client.executeGraphQLQuery(query)

        // Verify the query has errors due to restricted fields
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

        val response = client.executeGraphQLQuery(query)

        // Verify the query executed successfully for non-scoped fields
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
}
