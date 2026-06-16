package viaduct.tenant.runtime.execution.batchresolver.fieldresolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.ItemResolvers;
import viaduct.tenant.runtime.execution.batchresolver.fieldresolver.resolverbases.QueryResolvers;

public class JavaFieldBatchResolverContractTest extends FieldBatchResolverContractTest {

  @Override
  protected void setBatchedFieldShouldReturnTenantException(boolean enabled) {
    BatchedFieldResolver.shouldReturnTenantException = enabled;
  }

  // --- Resolvers ---

  @Resolver
  public static class ItemsResolver extends QueryResolvers.Items {
    @Override
    public CompletableFuture<List<Item>> resolve(Context ctx) {
      int count = ctx.getArguments().getCount() != null ? ctx.getArguments().getCount() : 2;
      List<Item> items =
          IntStream.rangeClosed(1, count)
              .mapToObj(i -> Item.builder().id("item-" + i).build())
              .toList();
      return CompletableFuture.completedFuture(items);
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Item { id }")
  public static class BatchedFieldResolver extends ItemResolvers.BatchedField {
    static volatile boolean shouldReturnTenantException = false;

    @Override
    public CompletableFuture<Map<Context, String>> batchResolve(List<Context> contexts) {
      if (shouldReturnTenantException) {
        // Sneaky-throw: TenantUsageException is checked in Java, but the base signature doesn't
        // declare it. The framework's error handler normalises it to a field error.
        sneakyThrow(new TenantUsageException("field api misuse", null));
      }
      Map<Context, String> results = new LinkedHashMap<>();
      for (Context ctx : contexts) {
        String itemId = ctx.getObjectValue().getId();
        results.put(ctx, "batched-" + itemId + "-size-" + contexts.size());
      }
      return CompletableFuture.completedFuture(results);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
      throw (E) e;
    }
  }

  @Resolver(objectValueFragment = "fragment _ on Item { id }")
  public static class ListFieldResolver extends ItemResolvers.ListField {
    @Override
    public CompletableFuture<Map<Context, List<Item>>> batchResolve(List<Context> contexts) {
      Map<Context, List<Item>> results = new LinkedHashMap<>();
      for (Context ctx : contexts) {
        String itemId = ctx.getObjectValue().getId();
        List<Item> subItems =
            IntStream.rangeClosed(1, contexts.size())
                .mapToObj(
                    i ->
                        Item.builder()
                            .id(itemId + "-list-" + i + "-size-" + contexts.size())
                            .build())
                .toList();
        results.put(ctx, subItems);
      }
      return CompletableFuture.completedFuture(results);
    }
  }
}
