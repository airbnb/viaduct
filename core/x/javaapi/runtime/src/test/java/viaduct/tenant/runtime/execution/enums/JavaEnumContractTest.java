package viaduct.tenant.runtime.execution.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.execution.enums.resolverbases.QueryResolvers;

public class JavaEnumContractTest extends EnumContractTest {

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
  public static class CurrentStatusResolver extends QueryResolvers.CurrentStatus {
    @Override
    public CompletableFuture<Status> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Status.ACTIVE);
    }
  }

  // --- Java-only test ---

  @Test
  public void allEnumValuesAreAccessible() {
    assertThat(Status.values()).hasSize(3);
    assertThat(Status.valueOf("ACTIVE")).isEqualTo(Status.ACTIVE);
    assertThat(Status.valueOf("INACTIVE")).isEqualTo(Status.INACTIVE);
    assertThat(Status.valueOf("PENDING")).isEqualTo(Status.PENDING);
  }
}
