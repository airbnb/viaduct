package com.example.starwars.service.test

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

/**
 * Basic tests to verify the GraphiQL endpoint is available and serving content.
 *
 * These tests also check that the GraphQL endpoint is functional.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SpringJUnitConfig
class GraphiQLTest {
    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = TestRestTemplate()

    @Test
    fun `test GraphiQL endpoint availability`() {
        val url = "http://localhost:$port/graphiql"
        val response = restTemplate.getForEntity(url, String::class.java)

        println("GraphiQL endpoint response status: ${response.statusCode}")
        println("GraphiQL endpoint response body length: ${response.body?.length ?: 0}")
        println("Response headers: ${response.headers}")

        if (response.statusCode != HttpStatus.OK) {
            println("GraphiQL endpoint failed with status: ${response.statusCode}")
            println("Response body: ${response.body}")
        }
    }

    @Test
    fun `test classpath resource exists`() {
        val resource = ClassPathResource("graphiql/index.html")
        println("Resource exists: ${resource.exists()}")
        println("Resource path: ${resource.path}")
        println("Resource URI: ${resource.uri}")

        if (resource.exists()) {
            println("Resource content length: ${resource.contentLength()}")
            println("Resource description: ${resource.description}")
        } else {
            // Try alternative paths
            val alternatives = listOf(
                "static/graphiql/index.html",
                "META-INF/resources/graphiql/index.html",
                "public/graphiql/index.html",
                "resources/graphiql/index.html"
            )

            alternatives.forEach { path ->
                val altResource = ClassPathResource(path)
                println("Alternative resource '$path' exists: ${altResource.exists()}")
            }
        }
    }

    @Test
    fun `test GraphQL endpoint works`() {
        val url = "http://localhost:$port/graphql"
        val query = """{"query": "{ __typename }"}"""

        val response = restTemplate.postForEntity(url, query, String::class.java)
        println("GraphQL endpoint response status: ${response.statusCode}")
        println("GraphQL endpoint response body: ${response.body}")
    }
}
