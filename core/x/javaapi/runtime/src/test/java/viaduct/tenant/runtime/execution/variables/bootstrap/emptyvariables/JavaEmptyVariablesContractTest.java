package viaduct.tenant.runtime.execution.variables.bootstrap.emptyvariables;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variables;
import viaduct.java.api.context.VariablesProviderContext;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.variables.VariablesProvider;
import viaduct.tenant.runtime.execution.variables.bootstrap.emptyvariables.resolverbases.QueryResolvers;

public class JavaEmptyVariablesContractTest extends EmptyVariablesContractTest {

  // --- Resolvers ---

  // Empty @Variables(types = {}) — should fail at bootstrap
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $someVar) }")
  public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.FromVariablesProvider.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {})
    public static class EmptyVariablesProvider implements VariablesProvider<Arguments.NoArguments> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.NoArguments> ctx) {
        return CompletableFuture.completedFuture(Map.of("someVar", 42));
      }
    }
  }

  @Resolver
  public static class IntermediaryResolver extends QueryResolvers.Intermediary {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Intermediary.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg());
    }
  }

  @Resolver
  public static class FromArgumentFieldResolver extends QueryResolvers.FromArgumentField {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.FromArgumentField.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg());
    }
  }
}
