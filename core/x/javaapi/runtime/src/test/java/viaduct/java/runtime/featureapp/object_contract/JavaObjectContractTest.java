package viaduct.java.runtime.featureapp.object_contract;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.FooResolvers;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.NestedFooResolvers;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.QueryResolvers;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.fixtures.ObjectContractTest;

public class JavaObjectContractTest extends ObjectContractTest {

  private final DefaultJavaResolverClassFinder classFinder =
      new DefaultJavaResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final JavaModuleBootstrapper bootstrapper =
      new JavaModuleBootstrapper(classFinder, TenantCodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
  }

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

  @Resolver
  public static class NestedResolver extends FooResolvers.Nested {
    @Override
    public CompletableFuture<NestedFoo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(NestedFoo.builder().build());
    }
  }

  @Resolver
  public static class ValueResolver extends NestedFooResolvers.Value {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("nested_value");
    }
  }

  @Resolver(objectValueFragment = "baz")
  public static class ShorthandBarResolver extends FooResolvers.ShorthandBar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getBaz());
    }
  }

  @Resolver(
      objectValueFragment =
          """
          fragment _ on Foo {
            baz
            nested {
              value
            }
          }
          """)
  public static class FragmentBarResolver extends FooResolvers.FragmentBar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      String baz = ctx.getObjectValue().getBaz();
      NestedFoo nested = ctx.getObjectValue().getNested();
      return CompletableFuture.completedFuture(baz + "-" + nested.getValue());
    }
  }

  @Resolver
  public static class FooListResolver extends QueryResolvers.FooList {
    @Override
    public CompletableFuture<List<Foo>> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          List.of(Foo.builder().build(), Foo.builder().build(), Foo.builder().build()));
    }
  }

  @Resolver
  public static class NestedFooListResolver extends QueryResolvers.NestedFooList {
    @Override
    public CompletableFuture<List<NestedFoo>> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          List.of(NestedFoo.builder().build(), NestedFoo.builder().build()));
    }
  }

  @Resolver
  public static class FooWithArgsResolver extends QueryResolvers.FooWithArgs {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      ctx.getArguments().getMessage();
      ctx.getArguments().getCount();
      return CompletableFuture.completedFuture(Foo.builder().build());
    }
  }

  @Resolver
  public static class MessageResolver extends FooResolvers.Message {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("message from resolver");
    }
  }
}
