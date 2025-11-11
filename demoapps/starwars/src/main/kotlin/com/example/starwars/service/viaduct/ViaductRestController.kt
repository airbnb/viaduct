package com.example.starwars.service.viaduct

import com.example.starwars.common.SecurityAccessContext
import graphql.ExecutionResult
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import kotlinx.coroutines.future.await
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
@Controller
class ViaductRestController(
    private val viaduct: Viaduct,
    private val securityAccessService: SecurityAccessContext
) {
    @Post("/graphql")
    suspend fun graphql(
        @Body request: Map<String, Any>,
        @Header(SCOPES_HEADER) scopesHeader: String?,
        @Header("security-access") securityAccess: String?
    ): HttpResponse<Map<String, Any>> {
        securityAccessService.setSecurityAccess(securityAccess)
        val executionInput = createExecutionInput(request)
        // tag::run_query[7] Runs the query example
        val scopes = parseScopes(scopesHeader)
        val schemaId = determineSchemaId(scopes)
        val result = viaduct.executeAsync(executionInput, schemaId).await()
        return HttpResponse.status<Map<String, Any>>(statusCode(result)).body(result.toSpecification())
    }

    // tag::parse_scopes[7] Parse scopes example

    /**
     * Extract the scopes from the request headers. If no scopes are provided, default to [DEFAULT_SCOPE].
     */
    private fun parseScopes(scopesHeader: String?): Set<String> {
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
    private fun createExecutionInput(request: Map<String, Any>): ExecutionInput {
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
