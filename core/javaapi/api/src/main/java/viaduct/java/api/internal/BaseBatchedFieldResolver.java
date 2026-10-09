package viaduct.java.api.internal;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.resolvers.FieldValue;

/** Common runtime contract implemented by generated batched field resolver bases. */
public interface BaseBatchedFieldResolver {
  CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>> invokeFieldBatchResolver(
      List<FieldExecutionContext<?, ?, ?, ?>> contexts);

  default CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, FieldValue<?>>>
      invokeFieldBatchResolverWithErrors(List<FieldExecutionContext<?, ?, ?, ?>> contexts) {
    return invokeFieldBatchResolver(contexts)
        .thenApply(results -> new IdentityHashMap<>(wrapValues(results)));
  }

  static <C, T> IdentityHashMap<C, FieldValue<T>> wrapValues(Map<C, T> results) {
    if (results == null) {
      throw new CompletionException(
          new TenantUsageException("batchResolve returned a null map", null));
    }
    IdentityHashMap<C, FieldValue<T>> wrapped = new IdentityHashMap<>();
    results.forEach((context, value) -> wrapped.put(context, FieldValue.ofValue(value)));
    return wrapped;
  }

  static <C, T>
      CompletableFuture<IdentityHashMap<FieldExecutionContext<?, ?, ?, ?>, T>> invokeBatch(
          List<FieldExecutionContext<?, ?, ?, ?>> contexts,
          Function<FieldExecutionContext<?, ?, ?, ?>, C> wrapContext,
          Function<List<C>, CompletableFuture<Map<C, T>>> resolve) {
    IdentityHashMap<C, FieldExecutionContext<?, ?, ?, ?>> wrappedToOriginal =
        new IdentityHashMap<>();
    List<C> wrappedContexts =
        contexts.stream()
            .map(
                context -> {
                  C wrapped = wrapContext.apply(context);
                  wrappedToOriginal.put(wrapped, context);
                  return wrapped;
                })
            .toList();
    return resolve
        .apply(wrappedContexts)
        .thenCompose(
            results -> {
              if (results == null) {
                return CompletableFuture.failedFuture(
                    new TenantUsageException("batchResolve returned a null map", null));
              }
              IdentityHashMap<FieldExecutionContext<?, ?, ?, ?>, T> translated =
                  new IdentityHashMap<>();
              for (var result : results.entrySet()) {
                FieldExecutionContext<?, ?, ?, ?> original = wrappedToOriginal.get(result.getKey());
                if (original == null) {
                  return failedForUnknownContext(result.getKey());
                }
                translated.put(original, result.getValue());
              }
              return CompletableFuture.completedFuture(translated);
            });
  }

  static <T> CompletableFuture<T> failedForUnknownContext(Object context) {
    return CompletableFuture.failedFuture(
        new TenantUsageException(
            "batchResolve returned a key that was not in the input context list: " + context,
            null));
  }
}
