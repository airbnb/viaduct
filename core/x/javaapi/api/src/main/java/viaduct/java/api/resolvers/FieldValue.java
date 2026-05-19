package viaduct.java.api.resolvers;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Represents the value of a resolved GraphQL field.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.FieldValue}. Used by batch resolvers to return
 * a per-element success or error for each input context.
 *
 * @param <T> the resolved value type
 */
public sealed interface FieldValue<T> permits FieldValue.Success, FieldValue.Error {

  /** Returns the value on success, or throws the contained exception on error. */
  T get();

  /** Whether this is an error value. */
  boolean isError();

  /** Constructs a {@link FieldValue} that resolved without an error. */
  static <T> FieldValue<T> ofValue(T value) {
    return new Success<>(value);
  }

  /** Constructs a {@link FieldValue} that resolved with the given error. */
  static <T> FieldValue<T> ofError(Exception error) {
    return new Error<>(error);
  }

  record Success<T>(T value) implements FieldValue<T> {
    @Override
    public T get() {
      return value;
    }

    @Override
    public boolean isError() {
      return false;
    }
  }

  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification =
          "Exception is intentionally stored and exposed by reference as the error payload.")
  record Error<T>(Exception error) implements FieldValue<T> {
    @Override
    public T get() {
      sneakyThrow(error);
      throw new IllegalStateException("unreachable");
    }

    @Override
    public boolean isError() {
      return true;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
      throw (E) t;
    }
  }
}
