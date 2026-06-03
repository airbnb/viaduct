package viaduct.tenant.runtime.execution.submutations;

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
import viaduct.tenant.runtime.execution.submutations.resolverbases.MutationResolvers;

public class JavaRecursiveSubmutationContractTest extends RecursiveSubmutationContractTest {

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
  public static class ExampleMutationSelectionsResolver
      extends MutationResolvers.ExampleMutationSelections {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int triangleSize = ctx.getArguments().getTriangleSize();
      if (triangleSize <= 1) {
        return CompletableFuture.completedFuture(1);
      }
      int next = triangleSize - 1;
      return ctx.mutation(
              "exampleMutationSelections(triangleSize: $n)", Map.of("n", next), Mutation.class)
          .thenApply(result -> triangleSize + result.getExampleMutationSelections());
    }
  }
}
