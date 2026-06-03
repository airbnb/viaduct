package viaduct.tenant.runtime.execution.subqueryvariables;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.java.runtime.bridge.ModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.ContainerResolvers;
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.QueryResolvers;

public class JavaSubqueryVariablesContractTest extends SubqueryVariablesContractTest {

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

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    public CompletableFuture<Container> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Container.builder().build());
    }
  }

  @Resolver
  public static class EchoInputResolver extends QueryResolvers.EchoInput {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      SubqueryInput input = ctx.getArguments().getInput();
      String statuses =
          input.getStatuses().stream()
              .map(SubqueryStatus::name)
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      return CompletableFuture.completedFuture(input.getCount() + ":" + statuses);
    }
  }

  @Resolver
  public static class QueryWithInputVariableResolver
      extends ContainerResolvers.QueryWithInputVariable {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      SubqueryInput input = ctx.getArguments().getInput();
      return ctx.query("echoInput(input: $input)", Map.of("input", input), Query.class)
          .thenApply(result -> result.getEchoInput() != null ? result.getEchoInput() : "");
    }
  }
}
