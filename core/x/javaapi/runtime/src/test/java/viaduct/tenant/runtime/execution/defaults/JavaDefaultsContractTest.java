package viaduct.tenant.runtime.execution.defaults;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;
import viaduct.tenant.runtime.execution.defaults.resolverbases.QueryResolvers;

public class JavaDefaultsContractTest extends DefaultsContractTest {

  // --- Resolvers ---

  @Resolver(objectValueFragment = "fragment _ on Query { inner(inp: {}) }")
  public static class Outer1Resolver extends QueryResolvers.Outer1 {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Outer1.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInnerOrThrow() * 3);
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Query { inner }")
  public static class Outer2Resolver extends QueryResolvers.Outer2 {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Outer2.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInnerOrThrow() * 5);
    }
  }

  @Resolver
  public static class Outer3Resolver extends QueryResolvers.Outer3 {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Outer3.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().getX() * 7);
    }
  }

  @Resolver(
      objectValueFragment = "fragment _ on Query { inner(inp: $var) } ",
      variables = {@Variable(name = "var", fromArgument = "arg")})
  public static class Outer4Resolver extends QueryResolvers.Outer4 {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Outer4.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInnerOrThrow() * 11);
    }
  }

  @Resolver
  public static class InnerResolver extends QueryResolvers.Inner {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Inner.Context ctx) {
      InputWithDefaults inp = ctx.getArguments().getInp();
      int result = inp != null ? inp.getX() * 2 : -1;
      return CompletableFuture.completedFuture(result);
    }
  }
}
