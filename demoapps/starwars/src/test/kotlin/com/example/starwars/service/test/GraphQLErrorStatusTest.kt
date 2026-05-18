package com.example.starwars.service.test

import io.kotest.matchers.shouldBe
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest
class GraphQLErrorStatusTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun `GraphQL errors return HTTP 200 not 4xx`() {
        val request = HttpRequest.POST(
            "/graphql",
            mapOf("query" to "query { thisFieldDoesNotExist }")
        ).contentType(MediaType.APPLICATION_JSON_TYPE)

        val response = client.toBlocking().exchange(request, String::class.java)

        response.status.code shouldBe 200
        val body = objectMapper.readTree(response.body())
        body.has("errors") shouldBe true
    }

    @Test
    fun `Malformed HTTP Request Unparseable JSON returns 400`() {
        val request = HttpRequest.POST("/graphql", "this is not json")
            .contentType(MediaType.APPLICATION_JSON_TYPE)

        val ex = runCatching {
            client.toBlocking().exchange(request, String::class.java)
        }.exceptionOrNull()

        (ex as HttpClientResponseException).status.code shouldBe 400
    }

    @Test
    fun `Malformed HTTP Request Missing Query Field returns 400`() {
        val request = HttpRequest.POST(
            "/graphql",
            mapOf("operationName" to "Hello")
        ).contentType(MediaType.APPLICATION_JSON_TYPE)

        val ex = runCatching {
            client.toBlocking().exchange(request, String::class.java)
        }.exceptionOrNull()

        (ex as HttpClientResponseException).status.code shouldBe 400
    }

    @Test
    fun `Malformed HTTP Request Wrong HTTP Method returns 405`() {
        val request = HttpRequest.create<String>(HttpMethod.PUT, "/graphql")
            .contentType(MediaType.APPLICATION_JSON_TYPE)
            .body("""{"query": "{ hero { name } }"}""")

        val ex = runCatching {
            client.toBlocking().exchange(request, String::class.java)
        }.exceptionOrNull()

        (ex as HttpClientResponseException).status.code shouldBe 405
    }
}
