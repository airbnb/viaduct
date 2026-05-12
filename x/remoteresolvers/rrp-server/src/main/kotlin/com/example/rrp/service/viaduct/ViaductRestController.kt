package com.example.rrp.service.viaduct

import com.example.starwars.common.SecurityAccessContext
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import kotlinx.coroutines.future.await
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct

private const val QUERY_FIELD = "query"
private const val VARIABLES_FIELD = "variables"
private const val SCOPES_HEADER = "X-Viaduct-Scopes"

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
    ): HttpResponse<Map<String, Any?>> {
        securityAccessService.setSecurityAccess(securityAccess)
        val executionInput = createExecutionInput(request)
        val scopes = parseScopes(scopesHeader)
        val schemaId = determineSchemaId(scopes)
        val result = viaduct.executeAsync(executionInput, schemaId).await()
        return HttpResponse.status<Map<String, Any?>>(statusCode(result)).body(result.toSpecification())
    }

    private fun parseScopes(scopesHeader: String?): Set<String> = scopesHeader?.split(",")?.map { it.trim() }?.toSet() ?: setOf(DEFAULT_SCOPE_ID)

    private fun determineSchemaId(scopes: Set<String>): SchemaId = if (scopes.contains(EXTRAS_SCOPE_ID)) EXTRAS_SCHEMA.schemaId else DEFAULT_SCHEMA.schemaId

    private fun createExecutionInput(request: Map<String, Any>): ExecutionInput {
        @Suppress("UNCHECKED_CAST")
        return ExecutionInput.create(
            operationText = request[QUERY_FIELD] as String,
            variables = (request[VARIABLES_FIELD] as? Map<String, Any>) ?: emptyMap(),
            requestContext = emptyMap<String, Any>(),
        )
    }

    private fun statusCode(result: ExecutionResult) =
        when {
            result.errors.isNotEmpty() -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.OK
        }
}
