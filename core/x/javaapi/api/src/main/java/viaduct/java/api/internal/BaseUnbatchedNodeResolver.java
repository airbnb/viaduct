package viaduct.java.api.internal;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.NodeExecutionContext;

/** Common runtime contract implemented by generated unbatched node resolver bases. */
public interface BaseUnbatchedNodeResolver {
  CompletableFuture<?> invokeNodeResolver(NodeExecutionContext<?> context);
}
