package viaduct.java.api.internal;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.NodeExecutionContext;

/** Common runtime contract implemented by generated batched node resolver bases. */
public interface BaseBatchedNodeResolver {
  CompletableFuture<?> invokeNodeBatchResolver(List<NodeExecutionContext<?>> contexts);
}
