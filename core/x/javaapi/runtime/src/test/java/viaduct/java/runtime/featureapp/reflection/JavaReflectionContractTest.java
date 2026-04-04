package viaduct.java.runtime.featureapp.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.featureapp.reflection.resolverbases.CategoryResolvers;
import viaduct.java.runtime.featureapp.reflection.resolverbases.QueryResolvers;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.fixtures.reflectioncontract.ReflectionContractTest;

public class JavaReflectionContractTest extends ReflectionContractTest {

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
  public static class CategoryResolver extends QueryResolvers.Category {
    @Override
    public CompletableFuture<Category> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          Category.builder().id(ctx.getArguments().getId()).build());
    }
  }

  @Resolver
  public static class CategoryProductsResolver extends CategoryResolvers.Products {
    @Override
    public CompletableFuture<List<Product>> resolve(Context ctx) {
      List<Product> products = new ArrayList<>();
      products.add(Toy.builder().id(123).prodType("Toy").build());
      products.add(Fruit.builder().id(123).prodType("Fruit").build());
      return CompletableFuture.completedFuture(products);
    }
  }
}
