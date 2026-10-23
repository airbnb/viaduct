package com.example.starwars.service.viaduct

import com.example.starwars.common.SecurityAccessContext
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct

/**
 * Query fields in the incoming GraphQL request. By default, GraphQL requests
 * contain a "query" field and optionally a "variables" field.
 */
private const val QUERY_FIELD = "query"
private const val VARIABLES_FIELD = "variables"

// tag::viaduct_graphql_controller Viaduct GraphQL Controller
@Controller
class ViaductRestController(
    private val viaduct: Viaduct,
    private val schemas: StarWarsSchemas,
    private val securityAccessService: SecurityAccessContext
) {
    // tag::schema_routes
    @Post("/graphql")
    suspend fun graphql(
        @Body request: Map<String, Any>,
        @Header("security-access") securityAccess: String?
    ): HttpResponse<Map<String, Any?>> = execute(request, schemas.default.schemaId, securityAccess)

    @Post("/extras/graphql")
    suspend fun extrasGraphql(
        @Body request: Map<String, Any>,
        @Header("security-access") securityAccess: String?
    ): HttpResponse<Map<String, Any?>> = execute(request, schemas.extras.schemaId, securityAccess)
    // end::schema_routes

    private suspend fun execute(
        request: Map<String, Any>,
        schemaId: SchemaId,
        securityAccess: String?
    ): HttpResponse<Map<String, Any?>> {
        val query = request[QUERY_FIELD] as? String
            ?: return HttpResponse.badRequest(
                mapOf("errors" to listOf(mapOf("message" to "Missing 'query' field")))
            )
        securityAccessService.setSecurityAccess(securityAccess)
        val executionInput = createExecutionInput(query, request)
        // tag::run_query
        val result = viaduct.execute(executionInput, schemaId)
        return HttpResponse.ok(result.toSpecification())
        // end::run_query
    }

    // end::viaduct_graphql_controller

    // tag::execution_input
    private fun createExecutionInput(
        query: String,
        request: Map<String, Any>
    ): ExecutionInput {
        @Suppress("UNCHECKED_CAST")
        return ExecutionInput.create(
            operationText = query,
            variables = (request[VARIABLES_FIELD] as? Map<String, Any>) ?: emptyMap(),
            requestContext = emptyMap<String, Any>(),
        )
    }
    // end::execution_input
}
