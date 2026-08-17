package viaduct.tenant.runtime.execution.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.tenant.runtime.execution.reflection.resolverbases.CategoryResolvers;
import viaduct.tenant.runtime.execution.reflection.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.reflection.resolverbases.ShelfResolvers;

public class JavaReflectionContractTest extends ReflectionContractTest {

  @Test
  void generatedMetadataDescriptorsMatchSchema() {
    assertEquals("Query", Query.Reflection.getName());
    assertSame(Query.class, Query.Reflection.getJavaClass());
    assertEquals("id", Toy.Fields.id.getName());
    assertSame(Toy.Reflection, Toy.Fields.id.getContainingType());

    CompositeField<Category, Product> products = Category.Fields.products;
    assertSame(Product.Reflection, products.getType());

    RootObjectField<Query, Category, Query_Category_Arguments> category = Query.Fields.category;
    assertSame(Category.Reflection, category.getType());
    assertEquals(List.of("category"), category.getPathFromQueryRoot());
  }

  // --- Resolvers ---

  @Resolver
  public static class CategoryResolver extends QueryResolvers.Category {
    @Override
    public CompletableFuture<Category> resolve(QueryResolvers.Category.Context ctx) {
      return CompletableFuture.completedFuture(
          Category.builder(ctx).id(ctx.getArguments().getId()).build());
    }
  }

  @Resolver
  public static class ShelfResolver extends QueryResolvers.Shelf {
    @Override
    public CompletableFuture<Shelf> resolve(QueryResolvers.Shelf.Context ctx) {
      return CompletableFuture.completedFuture(Shelf.builder(ctx).build());
    }
  }

  @Resolver
  public static class TopProductResolver extends ShelfResolvers.TopProduct {
    @Override
    public CompletableFuture<Product> resolve(ShelfResolvers.TopProduct.Context ctx) {
      return CompletableFuture.completedFuture(
          Toy.builder(ctx).id(1).prodType("action_figure").build());
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
    public CompletableFuture<String> resolve(ShelfResolvers.TopProductDescription.Context ctx) {
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
    public CompletableFuture<List<Product>> resolve(CategoryResolvers.Products.Context ctx) {
      List<Product> products = new ArrayList<>();
      products.add(Toy.builder(ctx).id(123).prodType("Toy").build());
      products.add(Fruit.builder(ctx).id(123).prodType("Fruit").build());
      return CompletableFuture.completedFuture(products);
    }
  }
}
