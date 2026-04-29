package viaduct.serve

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for the Viaduct Server GraphiQL endpoint and introspection.
 *
 * These tests verify that:
 * 1. The /graphiql endpoint serves the GraphiQL IDE HTML
 * 2. GraphQL introspection queries work correctly on /graphql
 * 3. The GraphiQL IDE can successfully fetch the schema
 */
class GraphiQLEndpointTest {
    private lateinit var helper: ViaductServerTestHelper

    @BeforeEach
    fun setup() {
        helper = ViaductServerTestHelper()
    }

    @AfterEach
    fun teardown() {
        helper.close()
    }

    @Test
    fun `GraphiQL endpoint should return HTML page`() {
        val response = helper.httpGet("/graphiql")
        assertEquals(200, response.statusCode(), "GraphiQL endpoint should return 200 OK")

        val html = response.body()
        assertTrue(html.contains("<!doctype html>", ignoreCase = true), "Should return valid HTML")
        assertTrue(html.contains("GraphiQL", ignoreCase = true), "Should reference GraphiQL")
        assertTrue(html.contains("/graphql"), "Should reference the GraphQL endpoint")
    }

    @Test
    fun `GraphQL endpoint should support introspection query`() {
        val result = helper.executeGraphQL(
            """
            {
                __schema {
                    queryType { name }
                    mutationType { name }
                }
            }
            """.trimIndent()
        )

        assertEquals(200, result.statusCode, "GraphQL endpoint should return 200 OK")
        assertNotNull(result.data, "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val schema = result.data!!["__schema"] as Map<String, Any>
        assertNotNull(schema["queryType"], "Schema should have queryType")
    }

    @Test
    fun `GraphQL endpoint should support __type introspection`() {
        val result = helper.executeGraphQL(
            """
            {
                __type(name: "Query") {
                    name
                    kind
                }
            }
            """.trimIndent()
        )

        assertEquals(200, result.statusCode, "GraphQL endpoint should return 200 OK")
        assertNotNull(result.data, "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val typeInfo = result.data!!["__type"] as Map<String, Any>
        assertEquals("Query", typeInfo["name"], "Type name should be Query")
        assertEquals("OBJECT", typeInfo["kind"], "Type kind should be OBJECT")
    }

    @Test
    fun `GraphQL endpoint should return errors array even on successful introspection`() {
        val result = helper.executeGraphQL(
            """
            {
                __schema {
                    queryType { name }
                }
            }
            """.trimIndent()
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.errors.isEmpty(), "Errors array should be empty for successful query")
    }

    @Test
    fun `JS files should be served from service-wiring resources`() {
        val jsFiles = listOf(
            "jsx-loader.js" to "loadJSX",
            "global-id-plugin.jsx" to "createGlobalIdPlugin"
        )

        for ((file, expectedContent) in jsFiles) {
            val response = helper.httpGet("/js/$file")
            assertEquals(200, response.statusCode(), "$file should return 200 OK")
            assertTrue(
                response.body().contains(expectedContent),
                "$file should contain '$expectedContent'"
            )
        }
    }
}
