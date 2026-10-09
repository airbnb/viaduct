package viaduct.java.api.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.api.types.NodeObject;

/** Common runtime contract implemented by generated batched node resolver bases. */
public interface BaseBatchedNodeResolver<R extends NodeObject> {
  CompletableFuture<Map<NodeExecutionContext<?>, FieldValue<R>>> invokeNodeBatchResolver(
      List<NodeExecutionContext<?>> contexts);
}
