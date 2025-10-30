package com.example.viadapp

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

/**
 * Simple GraphQL servlet that integrates directly with Viaduct.
 * This demonstrates how to create a GraphQL HTTP endpoint using Viaduct.
 */
class ViaductServlet(
    private val viaduct: Viaduct
) : HttpServlet() {
    private val objectMapper = ObjectMapper()

    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        val query = req.getParameter("query")
        if (query == null) {
            sendError(resp, 400, "Missing 'query' parameter for GET")
            return
        }

        val operationName = req.getParameter("operationName")
        val variablesParam = req.getParameter("variables")
        val variables = if (variablesParam.isNullOrBlank()) {
            emptyMap()
        } else {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(variablesParam, Map::class.java) as Map<String, Any>
        }

        executeGraphQL(query, variables, operationName, resp)
    }

    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        val contentType = req.contentType?.lowercase()?.split(";")?.get(0)?.trim()

        when (contentType) {
            null, "application/json", "application/graphql+json" -> handleJsonPost(req, resp)
            "application/graphql" -> handleGraphQLPost(req, resp)
            else -> sendError(resp, 415, "Unsupported Content-Type: $contentType")
        }
    }

    private fun handleJsonPost(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        val body = req.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        val requestMap = try {
            objectMapper.readValue(body, Map::class.java)
        } catch (e: Exception) {
            sendError(resp, 400, "Invalid JSON request: ${e.message}")
            return
        }

        val query = requestMap["query"] as? String
        if (query == null) {
            sendError(resp, 400, "Missing 'query' field")
            return
        }

        val operationName = requestMap["operationName"] as? String

        @Suppress("UNCHECKED_CAST")
        val variables = (requestMap["variables"] as? Map<String, Any>) ?: emptyMap()

        executeGraphQL(query, variables, operationName, resp)
    }

    private fun handleGraphQLPost(
        req: HttpServletRequest,
        resp: HttpServletResponse
    ) {
        val query = req.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        executeGraphQL(query, emptyMap(), null, resp)
    }

    private fun executeGraphQL(
        query: String,
        variables: Map<String, Any>,
        operationName: String?,
        resp: HttpServletResponse
    ) {
        val executionInput = ExecutionInput.create(
            operationText = query,
            variables = variables,
            operationName = operationName
        )
        val result = try {
            viaduct.executeAsync(executionInput).join()
        } catch (e: Exception) {
            sendError(resp, 500, e.message ?: "Execution error")
            return
        }
        // Convert to GraphQL spec format and send response
        val specResult = result.toSpecification()
        val statusCode = if (result.errors.isNotEmpty()) 400 else 200
        sendJson(resp, statusCode, specResult)
    }

    private fun sendJson(
        resp: HttpServletResponse,
        status: Int,
        data: Any
    ) {
        resp.status = status
        resp.contentType = "application/json; charset=utf-8"
        objectMapper.writeValue(resp.outputStream, data)
    }

    private fun sendError(
        resp: HttpServletResponse,
        status: Int,
        message: String
    ) {
        val errorResponse = mapOf(
            "data" to null,
            "errors" to listOf(mapOf("message" to message)),
        )
        sendJson(resp, status, errorResponse)
    }
}
