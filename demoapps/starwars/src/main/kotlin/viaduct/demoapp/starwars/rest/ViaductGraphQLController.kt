package viaduct.demoapp.starwars.rest

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
import viaduct.demoapp.starwars.Constants.DEFAULT_SCOPE
import viaduct.demoapp.starwars.Constants.INTROSPECTION_QUERY
import viaduct.demoapp.starwars.Constants.OPERATION_NAME_FIELD
import viaduct.demoapp.starwars.Constants.QUERY_FIELD
import viaduct.demoapp.starwars.Constants.SCOPES_HEADER
import viaduct.demoapp.starwars.Constants.VARIABLES_FIELD
import viaduct.demoapp.starwars.config.EXTRAS_SCOPE_ID
import viaduct.demoapp.starwars.config.SCHEMA_ID
import viaduct.demoapp.starwars.config.SCHEMA_ID_WITH_EXTRAS
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

@RestController
class ViaductGraphQLController {
    @Autowired
    lateinit var viaduct: Viaduct

    @PostMapping("/graphql")
    suspend fun graphql(
        @RequestBody request: Map<String, Any>,
        @RequestHeader headers: HttpHeaders
    ): ResponseEntity<Map<String, Any>> {
        val scopes = parseScopes(headers)
        val schemaId = determineSchemaId(scopes)
        val executionInput = createExecutionInput(request, schemaId)

        val result = viaduct.executeAsync(executionInput).await()

        return when {
            isIntrospectionQuery(request) -> {
                ResponseEntity.ok(mapOf("data" to result.getData<Map<String, Any>>()))
            }
            else -> {
                ResponseEntity.status(statusCode(result)).body(result.toSpecification())
            }
        }
    }

    private fun parseScopes(headers: HttpHeaders): Set<String> {
        val scopesHeader = headers.getFirst(SCOPES_HEADER)
        return if (scopesHeader != null) {
            scopesHeader.split(",").map { it.trim() }.toSet()
        } else {
            setOf(DEFAULT_SCOPE)
        }
    }

    private fun determineSchemaId(scopes: Set<String>): String {
        return if (scopes.contains(EXTRAS_SCOPE_ID)) {
            SCHEMA_ID_WITH_EXTRAS
        } else {
            SCHEMA_ID
        }
    }

    private fun createExecutionInput(
        request: Map<String, Any>,
        schemaId: String
    ): ExecutionInput {
        @Suppress("UNCHECKED_CAST")
        return ExecutionInput.create(
            schemaId = schemaId,
            operationText = request[QUERY_FIELD] as String,
            variables = (request[VARIABLES_FIELD] as? Map<String, Any>) ?: emptyMap(),
            requestContext = emptyMap<String, Any>(),
        )
    }

    private fun isIntrospectionQuery(request: Map<String, Any>): Boolean {
        return request[OPERATION_NAME_FIELD] == INTROSPECTION_QUERY
    }

    private fun statusCode(result: ExecutionResult) =
        when {
            result.isDataPresent && result.errors.isNotEmpty() -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.OK
        }
}
