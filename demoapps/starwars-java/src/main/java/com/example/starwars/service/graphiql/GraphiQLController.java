package com.example.starwars.service.graphiql;

import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import viaduct.service.wiring.graphiql.GraphiQLHtml;
import viaduct.service.wiring.graphiql.GraphiQLHtmlConfig;

/** Serves the GraphiQL interface for the Java Star Wars demo. */
@Controller
public final class GraphiQLController {
  private static final GraphiQLHtmlConfig CONFIG =
      new GraphiQLHtmlConfig(
          "GraphiQL - Star Wars Java",
          """
          query StarWarsCharacters {
            allCharacters(limit: 5) {
              id
              name
              homeworld {
                name
              }
            }
          }
          """,
          "starwars-java");

  @Get("/graphiql")
  @Produces(MediaType.TEXT_HTML)
  @Order(0)
  public HttpResponse<String> graphiql() {
    return HttpResponse.ok(GraphiQLHtml.graphiQLHtml(CONFIG)).contentType(MediaType.TEXT_HTML);
  }
}
