package viaduct.java.api.internal;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.FieldExecutionContext;

/** Common runtime contract implemented by generated unbatched field resolver bases. */
public interface BaseUnbatchedFieldResolver {
  CompletableFuture<?> invokeFieldResolver(FieldExecutionContext<?, ?, ?, ?> context);
}
