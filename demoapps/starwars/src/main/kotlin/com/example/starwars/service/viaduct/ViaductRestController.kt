package com.example.starwars.service.viaduct

import graphql.ExecutionResult
import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct

/**
 * Query fields in the incoming GraphQL request. By default, GraphQL requests
 * contain a "query" field and optionally a "variables" field.
 */
private const val QUERY_FIELD = "query"
private const val VARIABLES_FIELD = "variables"

/**
 * This demo includes a custom header to handle business rules.
 * X-Viaduct-Scopes represents additional scopes to include in the query
 * to filter which schemas to use in the Viaduct query to resolve the request.
 *
 * This is not part of the GraphQL spec, but is used here to demonstrate
 * how Viaduct can handle multi-schema scenarios based on request context.
 */
private const val SCOPES_HEADER = "X-Viaduct-Scopes"

/**
 * This controller handles incoming GraphQL requests and routes them to the appropriate Viaduct schema
 * based on the scopes provided in the request headers.
 */
// tag::viaduct_graphql_controller[18] Viaduct GraphQL Controller
@RestController
class ViaductRestController {
    @Autowired
    lateinit var viaduct: Viaduct

    @PostMapping("/graphql")
    suspend fun graphql(
        @RequestBody request: Map<String, Any>,
        @RequestHeader headers: HttpHeaders
    ): ResponseEntity<Map<String, Any>> {
        val executionInput = createExecutionInput(request)
        // tag::run_query[7] Runs the query example
        val scopes = parseScopes(headers)
        val schemaId = determineSchemaId(scopes)
        val result = viaduct.executeAsync(executionInput, schemaId).await()
        return ResponseEntity.status(statusCode(result)).body(result.toSpecification())
    }

    // tag::parse_scopes[7] Parse scopes example

    /**
     * Extract the scopes from the request headers. If no scopes are provided, default to [DEFAULT_SCOPE].
     */
    private fun parseScopes(headers: HttpHeaders): Set<String> {
        val scopesHeader = headers.getFirst(SCOPES_HEADER)
        return scopesHeader?.split(",")?.map { it.trim() }?.toSet() ?: setOf(DEFAULT_SCOPE_ID)
    }

    // tag::determine_schema[12] Determine schema example

    /**
     * Based on the scopes received in the request, determine which schema ID to use.
     * If the "extras" scope is included, use the schema that includes extra fields.
     */
    private fun determineSchemaId(scopes: Set<String>): SchemaId {
        return if (scopes.contains(EXTRAS_SCOPE_ID)) {
            EXTRAS_SCHEMA_ID
        } else {
            DEFAULT_SCHEMA_ID
        }
    }

    // tag::execution_input[16] Create ExecutionInput example

    /**
     * Create an [ExecutionInput] object from the incoming request map and the determined schema ID.
     *
     * Viaduct ExecutionInput is similar to the standard GraphQL ExecutionInput,
     * but includes the schema ID to specify which schema to use for execution.
     */
    private fun createExecutionInput(request: Map<String, Any>,): ExecutionInput {
        @Suppress("UNCHECKED_CAST")
        return ExecutionInput.create(
            operationText = request[QUERY_FIELD] as String,
            variables = (request[VARIABLES_FIELD] as? Map<String, Any>) ?: emptyMap(),
            requestContext = emptyMap<String, Any>(),
        )
    }

    /**
     * GraphQL usually responds with status code 200, here
     * an example of response post process handling, we are sending BAD_REQUEST status code.
     */
    private fun statusCode(result: ExecutionResult) =
        when {
            result.isDataPresent && result.errors.isNotEmpty() -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.OK
        }
}
