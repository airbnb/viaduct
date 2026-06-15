package viaduct.tenant.runtime.execution.fieldbatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.fieldbatch.resolverbases.ItemResolvers;
import viaduct.tenant.runtime.execution.fieldbatch.resolverbases.QueryResolvers;

public class JavaFieldBatchResolverContractTest extends FieldBatchResolverContractTest {

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
    @Override
    public CompletableFuture<Map<Context, String>> batchResolve(List<Context> contexts) {
      Map<Context, String> results = new LinkedHashMap<>();
      for (Context ctx : contexts) {
        String itemId = ctx.getObjectValue().getId();
        results.put(ctx, "batched-" + itemId + "-size-" + contexts.size());
      }
      return CompletableFuture.completedFuture(results);
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
