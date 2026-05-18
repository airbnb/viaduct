// tag::ktor-graphql-routing[55] Ktor GraphQL and GraphiQL routing setup
package com.example.viadapp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.future.await
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.ExecutionInput
import viaduct.service.wiring.graphiql.GraphiQLHtmlConfig
import viaduct.service.wiring.graphiql.graphiQLHtml

private val ktorStarterGraphiQLConfig = GraphiQLHtmlConfig(
    title = "GraphiQL - ktor-starter",
    defaultQuery = """
        query HelloWorld {
          greeting
          author
        }
    """.trimIndent(),
    storageKey = "ktor-starter",
)

private val viaduct by lazy {
    BasicViaductFactory.create(
        scopedSchemas = listOf(SchemaScopeInfo(SCHEMA_ID)),
    )
}

fun Application.configureRouting() {
    routing {
        route("/graphql") {
            post { // Extract GraphQL operation from HTTP request and pass to Viaduct for execution
                @Suppress("UNCHECKED_CAST")
                val request = call.receive<Map<String, Any?>>() as Map<String, Any>

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

                val result = viaduct.executeAsync(executionInput).await()
                call.respond(result.toSpecification())
            }
        }

        get("/graphiql") {
            call.respondText(graphiQLHtml(ktorStarterGraphiQLConfig), ContentType.Text.Html)
        }

        for (faviconFile in listOf("favicon.svg" to ContentType.Image.SVG, "favicon.ico" to ContentType("image", "x-icon"))) {
            val (name, contentType) = faviconFile
            get("/$name") {
                val resource = this::class.java.classLoader.getResource("graphiql/$name")
                if (resource == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respondBytes(resource.readBytes(), contentType)
            }
        }

        route("/js") {
            get("/{filename}") {
                val filename = call.parameters["filename"]
                if (filename == null || filename.contains('/') || filename.contains('\\')) {
                    call.respond(HttpStatusCode.NotFound, "JavaScript resource not found")
                    return@get
                }

                val resource = this::class.java.classLoader.getResource("graphiql/js/$filename")
                if (resource == null) {
                    call.respond(HttpStatusCode.NotFound, "JavaScript resource not found")
                    return@get
                }

                call.respondText(resource.readText(), ContentType.Text.JavaScript)
            }
        }
    }
}
