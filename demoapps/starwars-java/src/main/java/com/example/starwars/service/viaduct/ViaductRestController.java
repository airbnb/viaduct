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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.SchemaId;
import viaduct.service.api.Viaduct;

/** Routes GraphQL HTTP requests to the schema selected by request scopes. */
@Controller
public final class ViaductRestController {
  private static final String SCOPES_HEADER = "X-Viaduct-Scopes";

  private final Viaduct viaduct;

  public ViaductRestController(Viaduct viaduct) {
    this.viaduct = viaduct;
  }

  @Post("/graphql")
  public CompletableFuture<HttpResponse<Map<String, Object>>> graphql(
      @Body Map<String, Object> request,
      @Header(SCOPES_HEADER) @Nullable String scopesHeader,
      @Header("security-access") @Nullable String securityAccess) {
    Object queryValue = request.get("query");
    if (!(queryValue instanceof String query)) {
      return CompletableFuture.completedFuture(
          HttpResponse.badRequest(
              Map.of("errors", List.of(Map.of("message", "Missing 'query' field")))));
    }

    ExecutionInput executionInput =
        ExecutionInput.builder()
            .operationText(query)
            .variables(readVariables(request))
            .requestContext(
                Map.of(
                    SecurityAccessContext.REQUEST_CONTEXT_KEY,
                    new SecurityAccessContext(securityAccess)))
            .build();

    return viaduct
        .executeAsync(executionInput, determineSchemaId(parseScopes(scopesHeader)))
        .thenApply(result -> HttpResponse.ok(result.toSpecification()));
  }

  private Set<String> parseScopes(String scopesHeader) {
    if (scopesHeader == null) {
      return Set.of(ViaductConfiguration.DEFAULT_SCOPE_ID);
    }
    return Stream.of(scopesHeader.split(","))
        .map(String::trim)
        .collect(Collectors.toUnmodifiableSet());
  }

  private SchemaId determineSchemaId(Set<String> scopes) {
    return scopes.contains(ViaductConfiguration.EXTRAS_SCOPE_ID)
        ? ViaductConfiguration.EXTRAS_SCHEMA.getSchemaId()
        : ViaductConfiguration.DEFAULT_SCHEMA.getSchemaId();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readVariables(Map<String, Object> request) {
    Object variables = request.get("variables");
    return variables instanceof Map<?, ?>
        ? (Map<String, Object>) variables
        : Collections.emptyMap();
  }
}
