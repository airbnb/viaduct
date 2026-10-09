package viaduct.java.api.internal;

import java.util.concurrent.CompletableFuture;
import viaduct.apiannotations.InternalApi;
import viaduct.java.api.context.FieldExecutionContext;

/** Common runtime contract implemented by generated unbatched field resolver bases. */
@InternalApi
public interface BaseUnbatchedFieldResolver {
  CompletableFuture<?> invokeFieldResolver(FieldExecutionContext<?, ?, ?, ?> context);
}
