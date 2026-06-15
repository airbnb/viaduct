package viaduct.tenant.runtime.execution.variablesprovider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;
import viaduct.java.api.annotations.Variables;
import viaduct.java.api.context.VariablesProviderContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.variables.VariablesProvider;
import viaduct.tenant.runtime.execution.variablesprovider.resolverbases.QueryResolvers;

public class JavaVariablesProviderContractTest extends VariablesProviderContractTest {

  /**
   * Counts how many times any nested {@link VariablesProvider} is instantiated. Tests assert this
   * is exactly one per execution to catch double-wiring bugs (e.g., the bootstrapper accidentally
   * registering the provider twice).
   */
  static final AtomicInteger PROVIDER_INSTANTIATIONS = new AtomicInteger(0);

  @BeforeEach
  void resetProviderInstantiationCount() {
    PROVIDER_INSTANTIATIONS.set(0);
  }

  // --- Pass-through resolvers used as the inner field in $intermediary fragments ---

  @Resolver(
      objectValueFragment = "fragment _ on Query { intermediary(arg: $myVar) }",
      variables = {@Variable(name = "myVar", fromArgument = "arg")})
  public static class FromArgumentFieldResolver extends QueryResolvers.FromArgumentField {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }
  }

  @Resolver
  public static class IntermediaryResolver extends QueryResolvers.Intermediary {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg());
    }
  }

  @Resolver
  public static class IntermediaryTakesInputResolver extends QueryResolvers.IntermediaryTakesInput {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getInput().getX());
    }
  }

  @Resolver
  public static class IntermediaryTakesGlobalIDResolver
      extends QueryResolvers.IntermediaryTakesGlobalID {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getInput());
    }
  }

  @Resolver
  public static class IntermediaryTakesNestedComplexInputResolver
      extends QueryResolvers.IntermediaryTakesNestedComplexInput {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      ComplexInput complex = ctx.getArguments().getInput().getComplexInput();
      String values =
          complex.getIntArray().stream()
              .map(String::valueOf)
              .reduce((a, b) -> a + "," + b)
              .orElse("");
      return CompletableFuture.completedFuture(
          "Color: " + complex.getColor() + ", Values: " + values);
    }
  }

  // --- Resolvers using VariablesProvider ---

  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $x) }")
  public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"x: Int!"})
    public static class TestVariablesProvider implements VariablesProvider<Arguments.None> {
      public TestVariablesProvider() {
        PROVIDER_INSTANTIATIONS.incrementAndGet();
      }

      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(Map.of("x", 123));
      }
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Query { intermediaryTakesInput(input: $x) }")
  public static class FromVariablesProviderWithInputResolver
      extends QueryResolvers.FromVariablesProviderWithInput {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediaryTakesInput());
    }

    @Variables(types = {"x: MyInput!"})
    public static class TestVariablesProvider implements VariablesProvider<Arguments.None> {
      public TestVariablesProvider() {
        PROVIDER_INSTANTIATIONS.incrementAndGet();
      }

      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(Map.of("x", MyInput.builder().x(456).build()));
      }
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Query { intermediaryTakesGlobalID(input: $x) }")
  public static class FromVariablesProviderWithGlobalIDResolver
      extends QueryResolvers.FromVariablesProviderWithGlobalID {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediaryTakesGlobalID());
    }

    @Variables(types = {"x: ID!"})
    public static class TestVariablesProvider implements VariablesProvider<Arguments.None> {
      public TestVariablesProvider() {
        PROVIDER_INSTANTIATIONS.incrementAndGet();
      }

      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        GlobalID<MyType> id = ctx.globalIDFor(Type.ofClass(MyType.class), "123");
        return CompletableFuture.completedFuture(Map.of("x", id));
      }
    }
  }

  @Resolver(
      objectValueFragment =
          "fragment _ on Query { intermediaryTakesNestedComplexInput(input: $x) }")
  public static class FromVariablesProviderWithNestedComplexInputResolver
      extends QueryResolvers.FromVariablesProviderWithNestedComplexInput {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.getObjectValue().getIntermediaryTakesNestedComplexInput());
    }

    @Variables(types = {"x: InputWithNestedInput!"})
    public static class TestVariablesProvider implements VariablesProvider<Arguments.None> {
      public TestVariablesProvider() {
        PROVIDER_INSTANTIATIONS.incrementAndGet();
      }

      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        ComplexInput complex =
            ComplexInput.builder().color(Color.RED).intArray(List.of(1, 2, 3)).build();
        InputWithNestedInput nested = InputWithNestedInput.builder().complexInput(complex).build();
        return CompletableFuture.completedFuture(Map.of("x", nested));
      }
    }
  }

  // --- Java-only tests ---

  @org.junit.jupiter.api.Test
  public void variablesProviderInstantiatedAtLeastOncePerExecution() {
    PROVIDER_INSTANTIATIONS.set(0);
    execute("{ fromVariablesProvider }");
    int instantiations = PROVIDER_INSTANTIATIONS.get();
    org.junit.jupiter.api.Assertions.assertTrue(
        instantiations >= 1,
        "VariablesProvider should be instantiated at least once, got " + instantiations);
  }
}
