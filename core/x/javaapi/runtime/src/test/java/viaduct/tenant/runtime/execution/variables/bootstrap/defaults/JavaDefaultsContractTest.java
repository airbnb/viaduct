package viaduct.tenant.runtime.execution.variables.bootstrap.defaults;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;
import viaduct.tenant.runtime.execution.variables.bootstrap.defaults.resolverbases.QueryResolvers;

public class JavaDefaultsContractTest extends DefaultsContractTest {

  // --- Resolvers ---

  // outer1: uses object-value fragment requesting inner({}) with defaults filled in; returns inner
  // * 3
  @Resolver(objectValueFragment = "fragment _ on Query { inner(inp: {}) }")
  public static class Outer1Resolver extends QueryResolvers.Outer1 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 3);
    }
  }

  // outer2: uses object-value fragment requesting inner (null inp); inner returns -1, outer returns
  // -5
  @Resolver(objectValueFragment = "fragment _ on Query { inner }")
  public static class Outer2Resolver extends QueryResolvers.Outer2 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 5);
    }
  }

  // outer3: receives arg directly; returns arg.x * 7
  @Resolver
  public static class Outer3Resolver extends QueryResolvers.Outer3 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().getX() * 7);
    }
  }

  // outer4: uses fragment + variable forwarding arg to inner; returns inner * 11
  @Resolver(
      objectValueFragment = "fragment _ on Query { inner(inp: $var) }",
      variables = {@Variable(name = "var", fromArgument = "arg")})
  public static class Outer4Resolver extends QueryResolvers.Outer4 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 11);
    }
  }

  // inner: if inp is null return -1, else return inp.x * 2
  @Resolver
  public static class InnerResolver extends QueryResolvers.Inner {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      InputWithDefaults inp = ctx.getArguments().getInp();
      int result = inp != null ? inp.getX() * 2 : -1;
      return CompletableFuture.completedFuture(result);
    }
  }
}
