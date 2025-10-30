package com.example.viadapp.resolvers

import com.example.viadapp.JettyViaductApp
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.StringEntity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelloWorldTest {
    private lateinit var app: JettyViaductApp
    private var port: Int = 0
    private val objectMapper = ObjectMapper()

    @BeforeAll
    fun startServer() {
        // Find an available port
        port = ServerSocket(0).use { it.localPort }

        // Create and start the server
        app = JettyViaductApp(port)
        app.start()

        // Wait for server to start by polling the health endpoint
        waitForServerToStart()
    }

    private fun waitForServerToStart() {
        val maxAttempts = 50
        val delayBetweenAttempts = 100L
        var attempt = 0

        while (attempt < maxAttempts) {
            try {
                val httpClient = HttpClients.createDefault()
                val request = HttpPost("http://localhost:$port/graphql")
                request.entity = StringEntity("{\"query\": \"{ __typename }\"}", ContentType.APPLICATION_JSON)

                val isReady = httpClient.execute(request) { response ->
                    response.code == 200
                }

                if (isReady) {
                    return // Server is ready
                }
            } catch (e: Exception) {
                // Server not ready yet, continue polling
            }

            attempt++
            // Efficient sleep that doesn't violate lint rules
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayBetweenAttempts))
        }

        throw IllegalStateException("Server failed to start within ${maxAttempts * delayBetweenAttempts}ms")
    }

    @AfterAll
    fun stopServer() {
        app.stop()
    }

    private fun sendGraphQLRequest(query: String): Pair<Int, String> {
        val httpClient = HttpClients.createDefault()
        val request = HttpPost("http://localhost:$port/graphql")

        val requestBody = mapOf("query" to query)
        val json = objectMapper.writeValueAsString(requestBody)

        request.entity = StringEntity(json, ContentType.APPLICATION_JSON)
        request.setHeader("Accept", "application/json")
        request.setHeader("Content-Type", "application/json")

        return httpClient.execute(request) { response ->
            val statusCode = response.code
            val responseBody = response.entity.content.bufferedReader().readText()
            Pair(statusCode, responseBody)
        }
    }

    @Test
    fun `Query Greeting and Author`() {
        val query = """
            query HelloWorld {
                greeting
                author
            }
        """.trimIndent()

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 200
        responseBody shouldEqualJson """
            {
              "data": {
                "greeting": "Hello from Jetty + Viaduct!",
                "author": "Viaduct GraphQL with Jetty"
              }
            }
        """.trimIndent()
    }

    @Test
    fun `Query Single Greeting`() {
        val query = """
            query {
                greeting
            }
        """.trimIndent()

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 200
        responseBody shouldEqualJson """
            {
              "data": {
                "greeting": "Hello from Jetty + Viaduct!"
              }
            }
        """.trimIndent()
    }

    @Test
    fun `Error in Query Empty Body`() {
        val query = "query HelloWorld { }"

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 400
        responseBody shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Invalid syntax with offending token '}' at line 1 column 20",
                  "locations": [
                    {
                      "line": 1,
                      "column": 20
                    }
                  ],
                  "extensions": {
                    "classification": "InvalidSyntax"
                  }
                }
              ],
              "data": null
            }
        """.trimIndent()
    }

    @Test
    fun `Error in Query With Non Existing Field`() {
        val query = "query { thisIsNotAQuery }"

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 400
        responseBody shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Validation error (FieldUndefined@[thisIsNotAQuery]) : Field 'thisIsNotAQuery' in type 'Query' is undefined",
                  "locations": [
                    {
                      "line": 1,
                      "column": 9
                    }
                  ],
                  "extensions": {
                    "classification": "ValidationError"
                  }
                }
              ],
              "data": null
            }
        """.trimIndent()
    }

    @Test
    fun `Error Missing Query`() {
        val query = " "

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 400
        responseBody shouldEqualJson """
            {
              "errors": [
                {
                  "message": "Invalid syntax with offending token '<EOF>' at line 1 column 2",
                  "locations": [
                    {
                      "line": 1,
                      "column": 2
                    }
                  ],
                  "extensions": {
                    "classification": "InvalidSyntax"
                  }
                }
              ],
              "data": null
            }
        """.trimIndent()
    }

    @Test
    fun `Error Thrown from Tenant Resolver`() {
        val query = """
            query ThrowException {
                throwException
            }
        """.trimIndent()

        val (statusCode, responseBody) = sendGraphQLRequest(query)

        statusCode shouldBe 400
        responseBody shouldEqualJson """
            {
              "errors": [
                {
                  "message": "java.lang.IllegalStateException: This is a resolver error",
                  "locations": [
                    {
                      "line": 2,
                      "column": 5
                    }
                  ],
                  "path": [
                    "throwException"
                  ],
                  "extensions": {
                    "fieldName": "throwException",
                    "parentType": "Query",
                    "operationName": "ThrowException",
                    "classification": "DataFetchingException"
                  }
                }
              ],
              "data": {
                "throwException": null
              }
            }
        """.trimIndent()
    }
}
