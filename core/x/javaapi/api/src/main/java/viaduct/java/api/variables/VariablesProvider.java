package viaduct.java.api.variables;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.VariablesProviderContext;
import viaduct.java.api.types.Arguments;

/**
 * Java equivalent of Kotlin's {@code VariablesProvider}. Implementations dynamically compute values
 * for GraphQL variables that are referenced from a resolver's required selection set fragment.
 *
 * <p>An implementation must be a nested static class on a resolver and must be annotated with
 * {@link viaduct.java.api.annotations.Variables} declaring the variable names and types produced by
 * {@link #provide}.
 *
 * <p>The returned map must contain exactly the keys declared in the {@code @Variables} annotation.
 *
 * @param <A> The arguments type for the field whose resolver references this provider's variables.
 *     Use {@link Arguments.None} when the field has no arguments.
 */
@FunctionalInterface
public interface VariablesProvider<A extends Arguments> {

  /**
   * Compute and return the variable values.
   *
   * @param context Provides access to the field arguments and request context.
   * @return A future containing the resolved variable values, keyed by variable name.
   */
  CompletableFuture<Map<String, Object>> provide(VariablesProviderContext<A> context);
}
