package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation;

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
import viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation.resolverbases.QueryResolvers;

public class JavaTempOneOfViolationContractTest extends TempOneOfViolationContractTest {

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

  // Provides both stringValue and intValue in a @oneOf input — should fail at runtime
  @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $oneofVar) }")
  public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getIntermediary());
    }

    @Variables(types = {"oneofVar: OneofInput!"})
    public static class OneOfViolationProvider implements VariablesProvider<Arguments.None> {
      @Override
      public CompletableFuture<Map<String, Object>> provide(
          VariablesProviderContext<Arguments.None> ctx) {
        return CompletableFuture.completedFuture(
            Map.of("oneofVar", Map.of("stringValue", "test", "intValue", 42)));
      }
    }
  }

  @Resolver
  public static class IntermediaryResolver extends QueryResolvers.Intermediary {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().toString());
    }
  }

  @Resolver
  public static class FromArgumentFieldResolver extends QueryResolvers.FromArgumentField {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getArg().toString());
    }
  }
}
