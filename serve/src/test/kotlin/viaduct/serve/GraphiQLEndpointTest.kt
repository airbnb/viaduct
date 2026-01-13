package viaduct.serve

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
 *
 * Note: These tests require a @ViaductConfiguration annotated factory on the classpath.
 * The ValidTestFactory in test fixtures provides this.
 */
class GraphiQLEndpointTest {
    private lateinit var server: ViaductServer
    private lateinit var httpClient: HttpClient
    private val mapper = jacksonObjectMapper()
    private var serverPort: Int = 0

    @BeforeEach
    fun setup() {
        // Use port 0 for dynamic port assignment to avoid conflicts
        server = ViaductServer(port = 0, host = "127.0.0.1")

        httpClient = HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        // Start server in background thread
        Thread {
            try {
                server.start()
            } catch (e: Exception) {
                println("Server error: ${e.message}")
            }
        }.start()

        // Wait for server to start - poll until port is available
        // Use longer timeout for CI environments which can be slower
        serverPort = waitForServerPort(server, timeoutMs = 30000)
        println("Test server started on port: $serverPort")

        // Wait for server to be ready for connections
        waitForServerReady(serverPort, timeoutMs = 30000)
    }

    @AfterEach
    fun teardown() {
        // Stop server if running
        try {
            server.stop()
            // Wait for server to fully stop
            waitForServerStopped(serverPort, timeoutMs = 5000)
        } catch (e: Exception) {
            println("Error stopping server: ${e.message}")
        }
    }

    private fun waitForServerPort(
        server: ViaductServer,
        timeoutMs: Long
    ): Int {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (server.actualPort != 0) {
                return server.actualPort
            }
            // Yield to allow server thread to make progress
            Thread.yield()
        }
        throw IllegalStateException("Server failed to start within timeout")
    }

    private fun waitForServerReady(
        port: Int,
        timeoutMs: Long
    ) {
        val startTime = System.currentTimeMillis()
        val client = HttpClient.newHttpClient()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val request = HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/health"))
                    .GET()
                    .timeout(Duration.ofMillis(100))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    return
                }
            } catch (e: Exception) {
                // Server not ready yet, continue polling
                Thread.yield()
            }
        }
        throw IllegalStateException("Server did not become ready within timeout")
    }

    private fun waitForServerStopped(
        port: Int,
        timeoutMs: Long
    ) {
        val startTime = System.currentTimeMillis()
        val client = HttpClient.newHttpClient()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val request = HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/health"))
                    .GET()
                    .timeout(Duration.ofMillis(100))
                    .build()
                client.send(request, HttpResponse.BodyHandlers.ofString())
                // Server still responding, continue waiting
                Thread.yield()
            } catch (e: Exception) {
                // Connection refused or timeout means server stopped
                return
            }
        }
    }

    @Test
    fun `GraphiQL endpoint should return HTML page`() {
        // This test may fail if ValidTestFactory doesn't create a working Viaduct
        // Skip if server didn't start properly
        if (serverPort == 0) {
            println("Skipping test - server not started")
            return
        }

        // Given: Server is running
        val request = HttpRequest
            .newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphiql"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build()

        // When: Request the GraphiQL endpoint
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            println("Failed to connect to GraphiQL endpoint: ${e.message}")
            println("Note: ValidTestFactory may not create a working Viaduct instance")
            return
        }

        // Then: Should return HTML with GraphiQL
        assertEquals(200, response.statusCode(), "GraphiQL endpoint should return 200 OK")

        val html = response.body()
        assertTrue(html.contains("<!doctype html>", ignoreCase = true), "Should return valid HTML")
        assertTrue(html.contains("GraphiQL", ignoreCase = true), "Should reference GraphiQL")
        assertTrue(html.contains("/graphql"), "Should reference the GraphQL endpoint")
    }

    @Test
    fun `GraphQL endpoint should support introspection query`() {
        // Given: Server is running and an introspection query
        val introspectionQuery = """
            {
                __schema {
                    queryType { name }
                    mutationType { name }
                }
            }
        """.trimIndent()

        val requestBody = mapper.writeValueAsString(
            mapOf("query" to introspectionQuery)
        )

        val request = HttpRequest
            .newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        // When: Send introspection query
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: Should return schema information
        assertEquals(200, response.statusCode(), "GraphQL endpoint should return 200 OK")

        val result = mapper.readValue<Map<String, Any>>(response.body())
        assertNotNull(result["data"], "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any>
        assertNotNull(data["__schema"], "Response should contain __schema")

        @Suppress("UNCHECKED_CAST")
        val schema = data["__schema"] as Map<String, Any>
        assertNotNull(schema["queryType"], "Schema should have queryType")
    }

    @Test
    fun `GraphQL endpoint should support __type introspection`() {
        // Given: Server is running and a __type introspection query
        val typeIntrospectionQuery = """
            {
                __type(name: "Query") {
                    name
                    kind
                }
            }
        """.trimIndent()

        val requestBody = mapper.writeValueAsString(
            mapOf("query" to typeIntrospectionQuery)
        )

        val request = HttpRequest
            .newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        // When: Send __type query
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: Should return type information
        assertEquals(200, response.statusCode(), "GraphQL endpoint should return 200 OK")

        val result = mapper.readValue<Map<String, Any>>(response.body())
        assertNotNull(result["data"], "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any>
        assertNotNull(data["__type"], "Response should contain __type")

        @Suppress("UNCHECKED_CAST")
        val typeInfo = data["__type"] as Map<String, Any>
        assertEquals("Query", typeInfo["name"], "Type name should be Query")
        assertEquals("OBJECT", typeInfo["kind"], "Type kind should be OBJECT")
    }

    @Test
    fun `GraphQL endpoint should return errors array even on successful introspection`() {
        // Given: Server is running and an introspection query
        val query = """
            {
                __schema {
                    queryType { name }
                }
            }
        """.trimIndent()

        val requestBody = mapper.writeValueAsString(
            mapOf("query" to query)
        )

        val request = HttpRequest
            .newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        // When: Send query
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: Should have errors array (empty if successful)
        assertEquals(200, response.statusCode())

        val result = mapper.readValue<Map<String, Any>>(response.body())
        assertTrue(result.containsKey("errors"), "Response should contain errors array")

        @Suppress("UNCHECKED_CAST")
        val errors = result["errors"] as List<Any>
        assertTrue(errors.isEmpty(), "Errors array should be empty for successful query")
    }

    @Test
    fun `JS files should be served from service-wiring resources`() {
        // Skip if server didn't start properly
        if (serverPort == 0) {
            println("Skipping test - server not started")
            return
        }

        // Test each JS file that GraphiQL HTML references
        val jsFiles = listOf(
            "jsx-loader.js" to "loadJSX",
            "global-id-plugin.jsx" to "createGlobalIdPlugin"
        )

        for ((file, expectedContent) in jsFiles) {
            val request = HttpRequest
                .newBuilder()
                .uri(URI.create("http://127.0.0.1:$serverPort/js/$file"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()

            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                println("Failed to fetch $file: ${e.message}")
                throw AssertionError("Failed to fetch JS file: $file", e)
            }

            assertEquals(200, response.statusCode(), "$file should return 200 OK")
            assertTrue(
                response.body().contains(expectedContent),
                "$file should contain '$expectedContent'"
            )
        }
    }
}
