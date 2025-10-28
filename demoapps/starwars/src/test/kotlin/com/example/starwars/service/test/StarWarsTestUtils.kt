package com.example.starwars.service.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.globalid.GlobalIDImpl

val objectMapper = ObjectMapper()

/**
 * Generate a Global ID string for a given type and internal ID.
 */
fun Type<NodeCompositeOutput>.globalId(internalId: String): String {
    // Simple stub mirror for GlobalIDCodec (only used for serialization in tests)
    val globalIDCodec = GlobalIDCodecImpl(object : ReflectionLoader {
        override fun reflectionFor(name: String) = throw UnsupportedOperationException("Deserialization not needed in tests")

        override fun getGRTKClassFor(name: String) = throw UnsupportedOperationException("Deserialization not needed in tests")
    })

    val globalId = GlobalIDImpl(this, internalId)
    return globalIDCodec.serialize(globalId)
}

fun HttpClient.executeGraphQLQuery(
    query: String,
    headers: Map<String, String> = emptyMap(),
    scopes: Set<String>? = null
): JsonNode {
    var request: MutableHttpRequest<Map<String, String>> = HttpRequest.POST("/graphql", mapOf("query" to query))
        .contentType(MediaType.APPLICATION_JSON_TYPE)

    // Add any custom headers (for scopes, etc.)
    headers.forEach { (key, value) ->
        request = request.header(key, value)
    }

    // Add scope header if scopes are provided
    if (scopes != null) {
        request = request.header("X-Viaduct-Scopes", scopes.joinToString(","))
    }

    // Use exchange() instead of retrieve() to get the full response
    // This allows us to handle error responses without throwing exceptions
    try {
        val response: HttpResponse<String> = this.toBlocking().exchange(request, String::class.java)
        return objectMapper.readTree(response.body())
    } catch (e: HttpClientResponseException) {
        // If we get an HTTP error, try to parse the error response body
        // GraphQL servers may return errors in the body even with non-200 status codes
        val errorBody = e.response.getBody(String::class.java).orElse(null)
        if (errorBody != null) {
            return objectMapper.readTree(errorBody)
        }
        // If nobody is available, create a synthetic error response
        return objectMapper.createObjectNode().apply {
            set<JsonNode>(
                "errors",
                objectMapper.createArrayNode().apply {
                    add(
                        objectMapper.createObjectNode().apply {
                            put("message", "HTTP ${e.status.code}: ${e.message}")
                        }
                    )
                }
            )
        }
    }
}
