package viaduct.java.api.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.spi.TenantModuleException;
import viaduct.java.api.context.FieldExecutionContext;

/** Common runtime contract implemented by generated batched field resolver bases. */
public interface BaseBatchedFieldResolver {
  CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>> invokeFieldBatchResolver(
      List<FieldExecutionContext<?, ?, ?, ?>> contexts);

  static <T> CompletableFuture<T> failedForUnknownContext(Object context) {
    return CompletableFuture.failedFuture(
        new TenantModuleException(
            "batchResolve returned a key that was not in the input context list: " + context,
            null));
  }
}
