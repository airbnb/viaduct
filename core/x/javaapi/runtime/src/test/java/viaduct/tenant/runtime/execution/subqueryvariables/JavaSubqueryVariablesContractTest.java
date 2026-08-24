package viaduct.tenant.runtime.execution.subqueryvariables;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.ContainerResolvers;
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.QueryResolvers;

public class JavaSubqueryVariablesContractTest extends SubqueryVariablesContractTest {

  // --- Resolvers ---

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    public CompletableFuture<Container> resolve(QueryResolvers.Container.Context ctx) {
      return CompletableFuture.completedFuture(Container.builder(ctx).build());
    }
  }

  @Resolver
  public static class EchoInputResolver extends QueryResolvers.EchoInput {
    @Override
    public CompletableFuture<String> resolve(QueryResolvers.EchoInput.Context ctx) {
      SubqueryInput input = ctx.getArguments().getInput();
      String statuses =
          input.getStatuses().stream()
              .map(SubqueryStatus::name)
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      return CompletableFuture.completedFuture(input.getCount() + ":" + statuses);
    }
  }

  @Resolver
  public static class QueryWithInputVariableResolver
      extends ContainerResolvers.QueryWithInputVariable {
    @Override
    public CompletableFuture<String> resolve(
        ContainerResolvers.QueryWithInputVariable.Context ctx) {
      SubqueryInput input = ctx.getArguments().getInput();
      return ctx.query("echoInput(input: $input)", Map.of("input", input), Query.class)
          .thenApply(
              result -> result.getEchoInputOrThrow() != null ? result.getEchoInputOrThrow() : "");
    }
  }
}
