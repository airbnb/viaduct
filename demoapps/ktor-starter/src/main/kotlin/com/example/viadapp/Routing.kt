package com.example.viadapp

import com.example.viadapp.injector.ViaductConfiguration
import graphql.ExecutionResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.future.await
import viaduct.service.api.ExecutionInput

fun Application.configureRouting() {
    val viaduct = ViaductConfiguration.viaductService

    routing {
        get("/graphiql") {
            val resource = this::class.java.classLoader.getResource("graphiql/index.html")
            if (resource != null) {
                call.respondText(resource.readText(), ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.NotFound, "GraphiQL not found")
            }
        }

        route("/graphql") {
            post {
                @Suppress("UNCHECKED_CAST")
                val request = call.receive<Map<String, Any?>>() as Map<String, Any>

                // Validate query parameter
                val query = request["query"] as? String
                if (query == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("errors" to listOf(mapOf("message" to "Query parameter is required and must be a string")))
                    )
                    return@post
                }

                @Suppress("UNCHECKED_CAST")
                val executionInput = ExecutionInput.create(
                    operationText = query,
                    variables = (request["variables"] as? Map<String, Any>) ?: emptyMap(),
                )

                val result: ExecutionResult = viaduct.executeAsync(executionInput).await()

                when {
                    // This handles the introspection query returning the GraphQL Schema
                    request["operationName"] == "IntrospectionQuery" -> {
                        @Suppress("UNCHECKED_CAST")
                        val data = result.getData<Any>() as Map<String, Any>
                        call.respond(HttpStatusCode.OK, mapOf("data" to data))
                    }

                    else -> {
                        val statusCode = when {
                            result.isDataPresent && result.errors.isNotEmpty() -> HttpStatusCode.BadRequest
                            else -> HttpStatusCode.OK
                        }
                        call.respond(statusCode, result.toSpecification())
                    }
                }
            }
        }
    }
}
