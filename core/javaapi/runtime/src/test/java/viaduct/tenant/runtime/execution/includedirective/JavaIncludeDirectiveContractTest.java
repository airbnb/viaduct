package viaduct.tenant.runtime.execution.includedirective;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.includedirective.resolverbases.FooResolvers;
import viaduct.tenant.runtime.execution.includedirective.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.includedirective.resolverbases.ThrowerResolvers;

public class JavaIncludeDirectiveContractTest extends IncludeDirectiveContractTest {

  // --- Resolvers ---

  @Resolver
  public static class FooResolver extends QueryResolvers.Foo {
    @Override
    public CompletableFuture<Foo> resolve(QueryResolvers.Foo.Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder(ctx).build());
    }
  }

  @Resolver
  public static class ThrowerResolver extends QueryResolvers.Thrower {
    @Override
    public CompletableFuture<Thrower> resolve(QueryResolvers.Thrower.Context ctx) {
      return CompletableFuture.completedFuture(Thrower.builder(ctx).build());
    }
  }

  @Resolver
  public static class BooleanValueResolver extends QueryResolvers.BooleanValue {
    @Override
    public CompletableFuture<Boolean> resolve(QueryResolvers.BooleanValue.Context ctx) {
      return CompletableFuture.completedFuture(false);
    }
  }

  @Resolver
  public static class IntValueResolver extends FooResolvers.IntValue {
    @Override
    public CompletableFuture<Integer> resolve(FooResolvers.IntValue.Context ctx) {
      return CompletableFuture.completedFuture(10);
    }
  }

  @Resolver
  public static class SValueResolver extends FooResolvers.SValue {
    @Override
    public CompletableFuture<String> resolve(FooResolvers.SValue.Context ctx) {
      return CompletableFuture.completedFuture("result value");
    }
  }

  @Resolver
  public static class WillThrowResolver extends ThrowerResolvers.WillThrow {
    @Override
    public CompletableFuture<Integer> resolve(ThrowerResolvers.WillThrow.Context ctx) {
      throw new RuntimeException("asd");
    }
  }
}
