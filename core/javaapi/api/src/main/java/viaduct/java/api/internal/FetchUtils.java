package viaduct.java.api.internal;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import viaduct.apiannotations.InternalApi;

/** Asynchronous Java counterparts to Kotlin's fetch fallback helpers. */
@InternalApi
public final class FetchUtils {
  private FetchUtils() {}

  /** Returns the fetched value, or {@code null} when the fetch fails with an exception. */
  public static <T> CompletableFuture<T> fetchOrNull(
      Callable<? extends CompletionStage<? extends T>> block) {
    return fetchOrDefault(null, block);
  }

  /**
   * Returns the fetched value, or {@code defaultValue} when the fetch fails with an exception.
   * Successful {@code null} values are preserved. Cancellation and non-exception failures
   * propagate.
   */
  public static <T> CompletableFuture<T> fetchOrDefault(
      T defaultValue, Callable<? extends CompletionStage<? extends T>> block) {
    Objects.requireNonNull(block, "block");

    CompletionStage<? extends T> stage;
    try {
      stage = block.call();
    } catch (CancellationException exception) {
      return CompletableFuture.failedFuture(exception);
    } catch (Exception exception) {
      return CompletableFuture.completedFuture(defaultValue);
    }
    Objects.requireNonNull(stage, "block returned null");

    CompletableFuture<T> result = new CompletableFuture<>();
    stage.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            result.complete(value);
            return;
          }

          Throwable unwrapped = unwrapCompletionException(failure);
          if (unwrapped instanceof CancellationException cancellation) {
            result.completeExceptionally(cancellation);
          } else if (unwrapped instanceof Exception) {
            result.complete(defaultValue);
          } else {
            result.completeExceptionally(unwrapped);
          }
        });
    return result;
  }

  private static Throwable unwrapCompletionException(Throwable failure) {
    if (failure instanceof CompletionException completionException
        && completionException.getCause() != null) {
      return completionException.getCause();
    }
    return failure;
  }
}
