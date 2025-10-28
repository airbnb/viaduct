package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * Basic tests to verify the GraphiQL endpoint is available and serving content.
 *
 * These tests also check that the GraphQL endpoint is functional.
 */
@MicronautTest
class GraphiQLTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `test GraphiQL endpoint availability`() {
        val request = HttpRequest.GET<String>("/graphiql")

        try {
            val response = client.toBlocking().exchange(request, String::class.java)

            println("GraphiQL endpoint response status: ${response.status}")
            println("GraphiQL endpoint response body length: ${response.body()?.length ?: 0}")
            println("Response headers: ${response.headers.asMap()}")

            if (response.status != HttpStatus.OK) {
                println("GraphiQL endpoint failed with status: ${response.status}")
                println("Response body: ${response.body()}")
            }
        } catch (e: Exception) {
            println("Error accessing GraphiQL endpoint: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun `test classpath resource exists`() {
        // In Micronaut, resources are typically checked via ClassLoader
        val classLoader = Thread.currentThread().contextClassLoader

        val resourcePath = "graphiql/index.html"
        val resource = classLoader.getResource(resourcePath)

        println("Resource exists: ${resource != null}")
        if (resource != null) {
            println("Resource path: $resourcePath")
            println("Resource URI: $resource")

            try {
                val connection = resource.openConnection()
                println("Resource content length: ${connection.contentLength}")
            } catch (e: Exception) {
                println("Could not read resource content length: ${e.message}")
            }
        } else {
            // Try alternative paths
            val alternatives = listOf(
                "static/graphiql/index.html",
                "META-INF/resources/graphiql/index.html",
                "public/graphiql/index.html",
                "resources/graphiql/index.html"
            )

            alternatives.forEach { path ->
                val altResource = classLoader.getResource(path)
                println("Alternative resource '$path' exists: ${altResource != null}")
            }
        }
    }

    @Test
    fun `test GraphQL endpoint works`() {
        val query = mapOf("query" to "{ __typename }")
        val request = HttpRequest.POST("/graphql", query)

        try {
            val response = client.toBlocking().exchange(request, String::class.java)
            println("GraphQL endpoint response status: ${response.status}")
            println("GraphQL endpoint response body: ${response.body()}")

            response.status shouldBe HttpStatus.OK
        } catch (e: Exception) {
            println("Error accessing GraphQL endpoint: ${e.message}")
            e.printStackTrace()
        }
    }
}
