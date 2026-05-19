package viaduct.java.api.context;

import viaduct.java.api.types.Arguments;

/**
 * Context passed to a {@link viaduct.java.api.variables.VariablesProvider} when computing variable
 * values for a request. Provides access to the field arguments and the request context.
 *
 * @param <A> The arguments type for the field whose resolver references the provided variables.
 */
public interface VariablesProviderContext<A extends Arguments> extends ExecutionContext {

  /**
   * The arguments provided to the field whose resolver depends on the variables produced by this
   * provider.
   *
   * @return the typed arguments instance, or {@link Arguments#NoArguments} for fields with no
   *     arguments.
   */
  A getArguments();
}
