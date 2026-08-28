package com.example.starwars.service.graphiql;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Serves JavaScript resources used by the bundled GraphiQL plugins. */
@Controller("/js")
public final class StaticJsController {
  @Get("/jsx-loader.js")
  @Produces("application/javascript")
  public HttpResponse<String> jsxLoader() {
    return resource("graphiql/js/jsx-loader.js");
  }

  @Get("/global-id-plugin.jsx")
  @Produces("application/javascript")
  public HttpResponse<String> globalIdPlugin() {
    return resource("graphiql/js/global-id-plugin.jsx");
  }

  private HttpResponse<String> resource(String path) {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        return HttpResponse.notFound();
      }
      return HttpResponse.ok(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .contentType("application/javascript");
    } catch (IOException exception) {
      return HttpResponse.serverError();
    }
  }
}
