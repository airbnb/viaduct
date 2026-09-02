package viaduct.tenant.runtime.execution.backingdata;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.backingdata.resolverbases.FooResolvers;
import viaduct.tenant.runtime.execution.backingdata.resolverbases.QueryResolvers;

public class JavaBackingDataContractTest extends BackingDataContractTest {

  @Resolver
  public static class FooResolver extends QueryResolvers.Foo {
    @Override
    public CompletableFuture<Foo> resolve(QueryResolvers.Foo.Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder(ctx).build());
    }
  }

  @Resolver
  public static class BackingDataValueResolver extends FooResolvers.BackingDataValue {
    @Override
    public CompletableFuture<Object> resolve(FooResolvers.BackingDataValue.Context ctx) {
      return CompletableFuture.completedFuture(new BackingDataValue(10, "Hello, World!"));
    }
  }

  @Resolver(objectValueFragment = "backingDataValue")
  public static class IValueResolver extends FooResolvers.IValue {
    @Override
    public CompletableFuture<Integer> resolve(FooResolvers.IValue.Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.getObjectValue()
              .<BackingDataValue>get("backingDataValue", BackingDataValue.class)
              .i());
    }
  }

  @Resolver(
      objectValueFragment =
          """
          fragment _ on Foo {
            backingDataValue
          }
          """)
  public static class SValueResolver extends FooResolvers.SValue {
    @Override
    public CompletableFuture<String> resolve(FooResolvers.SValue.Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.getObjectValue()
              .<BackingDataValue>get("backingDataValue", BackingDataValue.class)
              .s());
    }
  }
}
