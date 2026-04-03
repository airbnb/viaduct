package viaduct.java.runtime.featureapp.include_directive;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.featureapp.include_directive.resolverbases.FooResolvers;
import viaduct.java.runtime.featureapp.include_directive.resolverbases.QueryResolvers;
import viaduct.java.runtime.featureapp.include_directive.resolverbases.ThrowerResolvers;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.fixtures.IncludeDirectiveContractTest;

public class JavaIncludeDirectiveContractTest extends IncludeDirectiveContractTest {

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
  public static class FooResolver extends QueryResolvers.Foo {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder().build());
    }
  }

  @Resolver
  public static class ThrowerResolver extends QueryResolvers.Thrower {
    @Override
    public CompletableFuture<Thrower> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Thrower.builder().build());
    }
  }

  @Resolver
  public static class BooleanValueResolver extends QueryResolvers.BooleanValue {
    @Override
    public CompletableFuture<Boolean> resolve(Context ctx) {
      return CompletableFuture.completedFuture(false);
    }
  }

  @Resolver
  public static class IntValueResolver extends FooResolvers.IntValue {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(10);
    }
  }

  @Resolver
  public static class SValueResolver extends FooResolvers.SValue {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("result value");
    }
  }

  @Resolver
  public static class WillThrowResolver extends ThrowerResolvers.WillThrow {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      throw new RuntimeException("asd");
    }
  }
}
