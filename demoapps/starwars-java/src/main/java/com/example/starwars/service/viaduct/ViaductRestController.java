package com.example.starwars.service.viaduct;

import com.example.starwars.common.SecurityAccessContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.SchemaId;
import viaduct.service.api.Viaduct;

// tag::viaduct_graphql_controller
@Controller
public final class ViaductRestController {
  private final Viaduct viaduct;
  private final ViaductConfiguration.StarWarsSchemas schemas;

  public ViaductRestController(Viaduct viaduct, ViaductConfiguration.StarWarsSchemas schemas) {
    this.viaduct = viaduct;
    this.schemas = schemas;
  }

  // tag::schema_routes
  @Post("/graphql")
  public CompletableFuture<HttpResponse<Map<String, Object>>> graphql(
      @Body Map<String, Object> request,
      @Header("security-access") @Nullable String securityAccess) {
    return execute(request, schemas.defaultSchema().getSchemaId(), securityAccess);
  }

  @Post("/extras/graphql")
  public CompletableFuture<HttpResponse<Map<String, Object>>> extrasGraphql(
      @Body Map<String, Object> request,
      @Header("security-access") @Nullable String securityAccess) {
    return execute(request, schemas.extras().getSchemaId(), securityAccess);
  }

  // end::schema_routes

  private CompletableFuture<HttpResponse<Map<String, Object>>> execute(
      Map<String, Object> request, SchemaId schemaId, @Nullable String securityAccess) {
    Object queryValue = request.get("query");
    if (!(queryValue instanceof String query)) {
      return CompletableFuture.completedFuture(
          HttpResponse.badRequest(
              Map.of("errors", List.of(Map.of("message", "Missing 'query' field")))));
    }

    // tag::execution_input
    ExecutionInput executionInput =
        ExecutionInput.builder()
            .operationText(query)
            .variables(readVariables(request))
            .requestContext(
                Map.of(
                    SecurityAccessContext.REQUEST_CONTEXT_KEY,
                    new SecurityAccessContext(securityAccess)))
            .build();
    // end::execution_input

    // tag::run_query
    return viaduct
        .executeAsync(executionInput, schemaId)
        .thenApply(result -> HttpResponse.ok(result.toSpecification()));
    // end::run_query
  }

  // end::viaduct_graphql_controller

  @SuppressWarnings("unchecked")
  private Map<String, Object> readVariables(Map<String, Object> request) {
    Object variables = request.get("variables");
    return variables instanceof Map<?, ?>
        ? (Map<String, Object>) variables
        : Collections.emptyMap();
  }
}
