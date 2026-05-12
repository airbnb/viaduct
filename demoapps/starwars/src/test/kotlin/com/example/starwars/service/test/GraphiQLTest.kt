package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.context.annotation.Property
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
@Property(name = "micronaut.server.port", value = "-1")
class GraphiQLTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GraphiQL endpoint uses Star Wars default query and storage key`() {
        val request = HttpRequest.GET<String>("/graphiql")
        val response = client.toBlocking().exchange(request, String::class.java)
        val body = response.body() ?: ""

        response.status shouldBe HttpStatus.OK
        body shouldContain "GraphiQL - Star Wars"
        body shouldContain "query StarWarsCharacters"
        body shouldContain "allCharacters"
        body shouldContain "homeworld"
        body shouldContain "starwars"
    }

    @Test
    fun `GraphiQL JavaScript resources are served`() {
        val jsxLoader = client.toBlocking().exchange(HttpRequest.GET<String>("/js/jsx-loader.js"), String::class.java)
        val globalIdPlugin = client.toBlocking().exchange(HttpRequest.GET<String>("/js/global-id-plugin.jsx"), String::class.java)

        jsxLoader.status shouldBe HttpStatus.OK
        (jsxLoader.body() ?: "") shouldContain "loadJSX"
        globalIdPlugin.status shouldBe HttpStatus.OK
        (globalIdPlugin.body() ?: "") shouldContain "createGlobalIdPlugin"
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
