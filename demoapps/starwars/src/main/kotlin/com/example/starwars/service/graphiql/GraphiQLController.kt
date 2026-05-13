package com.example.starwars.service.graphiql

import io.micronaut.core.annotation.Order
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import viaduct.service.wiring.graphiql.GraphiQLHtmlConfig
import viaduct.service.wiring.graphiql.graphiQLHtml

private val starWarsGraphiQLConfig = GraphiQLHtmlConfig(
    title = "GraphiQL - Star Wars",
    defaultQuery = """
        query StarWarsCharacters {
          allCharacters(limit: 5) {
            id
            name
            homeworld {
              name
            }
          }
        }
    """.trimIndent(),
    storageKey = "starwars",
)

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
        return HttpResponse.ok(graphiQLHtml(starWarsGraphiQLConfig)).contentType(MediaType.TEXT_HTML)
    }
}
