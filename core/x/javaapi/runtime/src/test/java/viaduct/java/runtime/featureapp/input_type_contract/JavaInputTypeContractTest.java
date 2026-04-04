package viaduct.java.runtime.featureapp.input_type_contract;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.featureapp.input_type_contract.resolverbases.QueryResolvers;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.fixtures.inputtypecontract.InputTypeContractTest;

public class JavaInputTypeContractTest extends InputTypeContractTest {

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
  public static class UserByNameResolver extends QueryResolvers.UserByName {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      var args = ctx.getArguments();
      UserInput input = args.getInput();
      User user = User.builder().name(input.getName()).age(input.getAge()).build();
      return CompletableFuture.completedFuture(user);
    }
  }
}
