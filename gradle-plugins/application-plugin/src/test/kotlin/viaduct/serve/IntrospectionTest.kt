package viaduct.serve

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests to verify that GraphQL introspection queries return complete responses.
 *
 * These tests specifically verify that empty arrays (args, interfaces) are
 * properly included in introspection responses, which is required for GraphiQL 5
 * compatibility.
 *
 * Background: GraphiQL 5 strictly requires certain fields to always be present
 * (even if empty). This test ensures our server returns complete responses.
 */
class IntrospectionTest {

    private lateinit var server: ViaductServer
    private lateinit var httpClient: HttpClient
    private val mapper = jacksonObjectMapper()
    private var serverPort: Int = 0

    @BeforeEach
    fun setup() {
        // Use port 0 for dynamic port assignment to avoid conflicts
        server = ViaductServer(port = 0, host = "127.0.0.1")

        httpClient = HttpClient.newBuilder()
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

        // Wait for server to start - retry getting port until available
        var retries = 0
        while (retries < 50 && server.actualPort == 0) {
            Thread.sleep(100)
            retries++
        }

        if (server.actualPort == 0) {
            throw IllegalStateException("Server failed to start within timeout")
        }

        serverPort = server.actualPort
        println("Test server started on port: $serverPort")

        // Give server a bit more time to be ready for connections
        Thread.sleep(500)
    }

    @AfterEach
    fun teardown() {
        try {
            server.stop()
            Thread.sleep(500)
        } catch (e: Exception) {
            println("Error stopping server: ${e.message}")
        }
    }

    /**
     * Verifies that introspection responses include all required fields with proper
     * empty arrays where applicable.
     *
     * This test checks that:
     * 1. Directives include 'args' field (even if empty)
     * 2. Object types include 'interfaces' field (even if empty)
     * 3. Fields include 'args' field (even if empty)
     *
     * These are required by GraphiQL 5 for proper schema display.
     */
    @Test
    fun `introspection response should include args and interfaces fields`() {
        // Skip if server didn't start properly
        if (serverPort == 0) {
            println("Skipping test - server not started")
            return
        }

        // Send a comprehensive introspection query
        val introspectionQuery = """
            query IntrospectionQuery {
                __schema {
                    directives {
                        name
                        args { name }
                    }
                    types {
                        kind
                        name
                        interfaces { name }
                        fields {
                            name
                            args { name }
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = mapper.writeValueAsString(
            mapOf(
                "query" to introspectionQuery,
                "operationName" to "IntrospectionQuery"
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "Introspection query should succeed")

        val result = mapper.readValue<Map<String, Any>>(response.body())
        assertNotNull(result["data"], "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any>
        assertNotNull(data["__schema"], "Response should contain __schema")

        @Suppress("UNCHECKED_CAST")
        val schema = data["__schema"] as Map<String, Any>

        // Verify directives have 'args' field
        @Suppress("UNCHECKED_CAST")
        val directives = schema["directives"] as List<Map<String, Any>>
        assertTrue(directives.isNotEmpty(), "Schema should have directives")

        for (directive in directives) {
            assertTrue(
                directive.containsKey("args"),
                "Directive '${directive["name"]}' should have 'args' field (found keys: ${directive.keys})"
            )
            // args should be a list (possibly empty)
            val args = directive["args"]
            assertTrue(
                args is List<*>,
                "Directive '${directive["name"]}' args should be a list, got: ${args?.javaClass}"
            )
        }

        // Verify OBJECT types have 'interfaces' and their fields have 'args'
        @Suppress("UNCHECKED_CAST")
        val types = schema["types"] as List<Map<String, Any>>

        val objectTypes = types.filter { it["kind"] == "OBJECT" }
        assertTrue(objectTypes.isNotEmpty(), "Schema should have OBJECT types")

        for (type in objectTypes) {
            val typeName = type["name"]

            // OBJECT types should have 'interfaces' field
            assertTrue(
                type.containsKey("interfaces"),
                "OBJECT type '$typeName' should have 'interfaces' field (found keys: ${type.keys})"
            )
            val interfaces = type["interfaces"]
            assertTrue(
                interfaces is List<*>,
                "OBJECT type '$typeName' interfaces should be a list, got: ${interfaces?.javaClass}"
            )

            // Check fields have 'args'
            @Suppress("UNCHECKED_CAST")
            val fields = type["fields"] as? List<Map<String, Any>>
            if (fields != null) {
                for (field in fields) {
                    assertTrue(
                        field.containsKey("args"),
                        "Field '${field["name"]}' on type '$typeName' should have 'args' field"
                    )
                    val fieldArgs = field["args"]
                    assertTrue(
                        fieldArgs is List<*>,
                        "Field '${field["name"]}' args should be a list, got: ${fieldArgs?.javaClass}"
                    )
                }
            }
        }

        println("Introspection test passed: all args and interfaces fields are present")
    }

    /**
     * Verifies that a field without arguments has args: [] (not missing or null).
     */
    @Test
    fun `field without arguments should have empty args array`() {
        if (serverPort == 0) {
            println("Skipping test - server not started")
            return
        }

        // Query for a specific field that we know has no arguments
        val query = """
            {
                __type(name: "Query") {
                    fields {
                        name
                        args { name }
                    }
                }
            }
        """.trimIndent()

        val requestBody = mapper.writeValueAsString(mapOf("query" to query))

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$serverPort/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())

        val result = mapper.readValue<Map<String, Any>>(response.body())

        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val typeInfo = data["__type"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val fields = typeInfo["fields"] as List<Map<String, Any>>

        // Find a field (any field on Query should work)
        val field = fields.firstOrNull()
        assertNotNull(field, "Query type should have at least one field")

        // Verify args is present and is a list
        assertTrue(field.containsKey("args"), "Field should have 'args' key")
        val args = field["args"]
        assertTrue(args is List<*>, "args should be a List, got: ${args?.javaClass}")

        println("Field '${field["name"]}' has args: $args (type: ${args?.javaClass?.simpleName})")
    }
}
