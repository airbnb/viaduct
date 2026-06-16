package viaduct.tenant.runtime.execution.invalidfragment.objectfragment;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.FooResolvers;
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.QueryResolvers;

public class JavaInvalidObjectFragmentContractTest extends InvalidObjectFragmentContractTest {

  // --- Resolvers ---

  @Resolver
  public static class GreetingResolver extends QueryResolvers.Greeting {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder().build());
    }
  }

  @Resolver
  public static class BazResolver extends FooResolvers.Baz {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("world");
    }
  }

  // Object value fragment is intentionally invalid: the schema for Foo only has bar/baz.
  @Resolver(objectValueFragment = "horse")
  public static class BarResolver extends FooResolvers.Bar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("unreachable");
    }
  }
}
