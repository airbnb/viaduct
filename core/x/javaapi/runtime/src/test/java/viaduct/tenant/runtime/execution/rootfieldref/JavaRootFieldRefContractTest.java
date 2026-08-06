package viaduct.tenant.tutorial13;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.types.Arguments;
import viaduct.tenant.tutorial13.resolverbases.ProductFactoryResolvers;
import viaduct.tenant.tutorial13.resolverbases.QueryResolvers;

public class JavaRootFieldRefContractTest extends RootFieldRefContractTest {

  @SuppressWarnings("deprecation")
  @Test
  void noArgumentsAliasesRemainCompatible() {
    assertSame(Arguments.None, Arguments.NoArguments);
    assertTrue(Arguments.isNoArgumentsClass(Arguments.None.class));
    assertTrue(Arguments.isNoArgumentsClass(Arguments.NoArguments.class));
  }

  @Test
  void rootFieldRefResolvesNoArgumentsAndNestedReferencesThroughNamespaceTypes() {
    var result = execute("{ product { name price related { name price metadata } } }");

    assertTrue(result.getErrors().isEmpty());
    assertEquals(
        Map.of(
            "product",
            Map.of(
                "name",
                "Container",
                "price",
                0,
                "related",
                Map.of(
                    "name",
                    "Widget",
                    "price",
                    42,
                    "metadata",
                    Map.of("source", "catalog", "scores", List.of(1, 2))))),
        result.getData());
  }

  @Resolver
  public static class ProductFactoryCreateResolver extends ProductFactoryResolvers.Create {
    @Override
    public CompletableFuture<Product> resolve(Context ctx) {
      var arguments = productArguments(ctx);
      var related = ctx.rootFieldRef(ProductFactory.Fields.createWithArguments, arguments);

      return CompletableFuture.completedFuture(
          Product.builder(ctx).name("Container").price(0).related(related).build());
    }
  }

  @Resolver
  public static class ProductFactoryCreateWithArgumentsResolver
      extends ProductFactoryResolvers.CreateWithArguments {
    @Override
    public CompletableFuture<Product> resolve(Context ctx) {
      var arguments = ctx.getArguments();

      assertEquals("Widget", arguments.getName());
      assertEquals(Map.of("source", "catalog", "scores", List.of(1, 2)), arguments.getMetadata());
      assertEquals(42, arguments.getSpec().getQuantity());
      assertEquals(ProductKind.PHYSICAL, arguments.getKind());
      assertEquals(List.of("featured", "new"), arguments.getTags());
      assertEquals("owner-1", arguments.getOwnerId().getInternalID());

      return CompletableFuture.completedFuture(
          Product.builder(ctx)
              .name(arguments.getName())
              .price(arguments.getSpec().getQuantity())
              .metadata(arguments.getMetadata())
              .build());
    }
  }

  @Resolver
  public static class ProductResolver extends QueryResolvers.Product {
    @Override
    public CompletableFuture<Product> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.rootFieldRef(ProductFactory.Fields.create, Arguments.None));
    }
  }

  private static ProductFactory_CreateWithArguments_Arguments productArguments(
      ProductFactoryResolvers.Create.Context ctx) {
    return ProductFactory_CreateWithArguments_Arguments.builder(ctx)
        .name("Widget")
        .metadata(Map.of("source", "catalog", "scores", List.of(1, 2)))
        .spec(ProductSpecInput.builder(ctx).quantity(42).build())
        .kind(ProductKind.PHYSICAL)
        .tags(List.of("featured", "new"))
        .ownerId(ctx.globalIDFor(Owner.Reflection, "owner-1"))
        .build();
  }
}
