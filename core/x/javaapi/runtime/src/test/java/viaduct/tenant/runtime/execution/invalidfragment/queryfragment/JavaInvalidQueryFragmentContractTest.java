package viaduct.tenant.runtime.execution.invalidfragment.queryfragment;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.invalidfragment.queryfragment.resolverbases.FooResolvers;
import viaduct.tenant.runtime.execution.invalidfragment.queryfragment.resolverbases.QueryResolvers;

public class JavaInvalidQueryFragmentContractTest extends InvalidQueryFragmentContractTest {

  // --- Resolvers ---

  @Resolver
  public static class GreetingResolver extends QueryResolvers.Greeting {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder(ctx).build());
    }
  }

  @Resolver
  public static class BazResolver extends FooResolvers.Baz {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("world");
    }
  }

  // Query value fragment is intentionally invalid: schema for Query has only `greeting`.
  @Resolver(queryValueFragment = "horse")
  public static class BarResolver extends FooResolvers.Bar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("unreachable");
    }
  }
}
