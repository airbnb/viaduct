package viaduct.tenant.runtime.execution.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.java.runtime.bridge.ModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.tenant.runtime.execution.reflection.resolverbases.CategoryResolvers;
import viaduct.tenant.runtime.execution.reflection.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.reflection.resolverbases.ShelfResolvers;

public class JavaReflectionContractTest extends ReflectionContractTest {

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
  public static class CategoryResolver extends QueryResolvers.Category {
    @Override
    public CompletableFuture<Category> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          Category.builder().id(ctx.getArguments().getId()).build());
    }
  }

  @Resolver
  public static class ShelfResolver extends QueryResolvers.Shelf {
    @Override
    public CompletableFuture<Shelf> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Shelf.builder().build());
    }
  }

  @Resolver
  public static class TopProductResolver extends ShelfResolvers.TopProduct {
    @Override
    public CompletableFuture<Product> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          Toy.builder().id(1).prodType("action_figure").build());
    }
  }

  @Resolver(
      objectValueFragment =
          """
          fragment _ on Shelf {
            topProduct {
              ... on Toy { id prodType }
              ... on Fruit { id prodType }
            }
          }
          """)
  public static class TopProductDescriptionResolver extends ShelfResolvers.TopProductDescription {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      Product product = ctx.getObjectValue().getTopProduct();
      if (product instanceof Toy toy) {
        return CompletableFuture.completedFuture("Toy: " + toy.getProdType());
      } else if (product instanceof Fruit fruit) {
        return CompletableFuture.completedFuture("Fruit: " + fruit.getProdType());
      }
      return CompletableFuture.completedFuture("Unknown");
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
