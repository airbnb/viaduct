package viaduct.tenant.runtime.execution.defaults;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.execution.defaults.resolverbases.QueryResolvers;

public class JavaDefaultsContractTest extends DefaultsContractTest {

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

  @Resolver(objectValueFragment = "fragment _ on Query { inner(inp: {}) }")
  public static class Outer1Resolver extends QueryResolvers.Outer1 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 3);
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Query { inner }")
  public static class Outer2Resolver extends QueryResolvers.Outer2 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 5);
    }
  }

  @Resolver
  public static class Outer3Resolver extends QueryResolvers.Outer3 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().getX() * 7);
    }
  }

  @Resolver(
      objectValueFragment = "fragment _ on Query { inner(inp: $var) } ",
      variables = {@Variable(name = "var", fromArgument = "arg")})
  public static class Outer4Resolver extends QueryResolvers.Outer4 {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getInner() * 11);
    }
  }

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
