package viaduct.tenant.runtime.execution.variables.bootstrap.invalidsyntax;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variables;
import viaduct.java.api.context.VariablesProviderContext;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.variables.VariablesProvider;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.java.runtime.bridge.ModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.tenant.runtime.execution.variables.bootstrap.invalidsyntax.resolverbases.QueryResolvers;

public class JavaInvalidSyntaxContractTest extends InvalidSyntaxContractTest {

  private final DefaultResolverClassFinder classFinder =
      new DefaultResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final ModuleBootstrapper bootstrapper =
      new ModuleBootstrapper(classFinder, CodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>
      createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
  }

  // --- Resolvers ---

  // Invalid syntax in @Variables(types = ...) — should fail at bootstrap
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $someVar) }")
  public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"someVar Int! invalid syntax here"})
    public static class InvalidSyntaxProvider implements VariablesProvider<Arguments.None> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(Map.of("someVar", 42));
      }
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
  public static class FromArgumentFieldResolver extends QueryResolvers.FromArgumentField {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg());
    }
  }
}
