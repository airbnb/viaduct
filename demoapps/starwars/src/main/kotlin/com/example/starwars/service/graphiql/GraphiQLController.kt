package com.example.starwars.service.graphiql

import io.micronaut.core.annotation.Order
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces

/**
 * Minimal GraphiQL Web interface to interact with the Viaduct-powered GraphQL API.
 *
 * This controller serves the GraphiQL interface at the http://localhost:8080/graphiql endpoint.
 */
@Controller
class GraphiQLController {
    @Get("/graphiql")
    @Produces(MediaType.TEXT_HTML)
    @Order(0)
    fun graphiql(): HttpResponse<String> {
        val resource = this::class.java.classLoader.getResource("graphiql/index.html")
        val content = resource?.readText() ?: return HttpResponse.notFound()
        return HttpResponse.ok(content).contentType(MediaType.TEXT_HTML) // AND THIS
    }
}
