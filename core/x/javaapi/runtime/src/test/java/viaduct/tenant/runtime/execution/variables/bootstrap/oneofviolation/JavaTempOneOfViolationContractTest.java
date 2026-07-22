package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variables;
import viaduct.java.api.context.VariablesProviderContext;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.variables.VariablesProvider;
import viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation.resolverbases.QueryResolvers;

public class JavaTempOneOfViolationContractTest extends TempOneOfViolationContractTest {

  // --- Resolvers ---

  // Provides both stringValue and intValue in a @oneOf input — should fail at runtime
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $oneofVar) }")
  public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"oneofVar: OneofInput!"})
    public static class OneOfViolationProvider implements VariablesProvider<Arguments.None> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(
            Map.of("oneofVar", Map.of("stringValue", "test", "intValue", 42)));
      }
    }
  }

  @Resolver
  public static class IntermediaryResolver extends QueryResolvers.Intermediary {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().toString());
    }
  }

  @Resolver
  public static class FromArgumentFieldResolver extends QueryResolvers.FromArgumentField {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().toString());
    }
  }

  // Builds a @oneOf input with two supplied keys (one null) via the generated Builder. build() must
  // fail fast: graphql-java counts supplied keys, not non-null values.
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $oneofVar) }")
  public static class FromBuilderTwoKeysOneNullResolver
      extends QueryResolvers.FromBuilderTwoKeysOneNull {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"oneofVar: OneofInput!"})
    public static class TwoKeysOneNullProvider implements VariablesProvider<Arguments.None> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(
            Map.of("oneofVar", OneofInput.builder(ctx).stringValue("test").intValue(null).build()));
      }
    }
  }

  // Builds a @oneOf input with a single supplied key whose value is null. build() must fail fast.
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $oneofVar) }")
  public static class FromBuilderSingleNullKeyResolver
      extends QueryResolvers.FromBuilderSingleNullKey {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"oneofVar: OneofInput!"})
    public static class SingleNullKeyProvider implements VariablesProvider<Arguments.None> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(
            Map.of("oneofVar", OneofInput.builder(ctx).stringValue(null).build()));
      }
    }
  }
}
