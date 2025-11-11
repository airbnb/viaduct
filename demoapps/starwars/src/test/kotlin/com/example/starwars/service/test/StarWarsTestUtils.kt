package com.example.starwars.service.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import viaduct.api.mocks.testGlobalId as GlobalId
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput

val objectMapper = ObjectMapper()

/**
 * Generate a Global ID string for a given type and internal ID.
 */
fun Type<NodeCompositeOutput>.globalId(internalId: String): String {
    return GlobalId(internalId)
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

/**
 * Execute a GraphQL query with admin access header.
 */
fun HttpClient.executeGraphQLQueryWithAdminAccess(
    query: String,
    scopes: Set<String>? = null
): JsonNode {
    return executeGraphQLQuery(
        query = query,
        headers = mapOf("security-access" to "admin"),
        scopes = scopes
    )
}

/**
 * Execute a GraphQL query with a custom security access header.
 */
fun HttpClient.executeGraphQLQueryWithCustomAccess(
    query: String,
    securityAccess: String,
    scopes: Set<String>? = null
): JsonNode {
    return executeGraphQLQuery(
        query = query,
        headers = mapOf("security-access" to securityAccess),
        scopes = scopes
    )
}
