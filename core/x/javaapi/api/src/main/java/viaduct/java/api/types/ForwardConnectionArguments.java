package viaduct.java.api.types;

import viaduct.tenant.runtime.support.ConnectionArgumentsSupport;

/**
 * Arguments for forward pagination through a connection.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.ForwardConnectionArguments}. The math and
 * validation are delegated to the language-neutral {@link ConnectionArgumentsSupport} shared with
 * the Kotlin Tenant API.
 *
 * @see BackwardConnectionArguments
 */
public interface ForwardConnectionArguments extends ConnectionArguments {
  /** Maximum number of items to return from the beginning; null for the default page size. */
  Integer getFirst();

  /** Cursor to start fetching items after (exclusive); null to start from the beginning. */
  String getAfter();

  /**
   * Converts forward pagination arguments to offset/limit.
   *
   * <ul>
   *   <li>{@link #getFirst()} determines the page size (defaults to {@code defaultPageSize}).
   *   <li>{@link #getAfter()} cursor encodes the index of the last-seen item; this decodes it and
   *       adds 1 so the returned offset points to the first item <i>after</i> that cursor.
   *   <li>If {@code after} is absent, pagination starts from offset 0.
   * </ul>
   */
  @Override
  default OffsetLimit toOffsetLimit(int defaultPageSize) {
    ConnectionArgumentsSupport.OffsetBounds b =
        ConnectionArgumentsSupport.INSTANCE.forwardOffsetLimit(
            getFirst(), getAfter(), defaultPageSize);
    return new OffsetLimit(b.getOffset(), b.getLimit());
  }

  /**
   * Validates forward pagination arguments.
   *
   * @throws IllegalArgumentException if first is not positive or after cursor is invalid
   */
  @Override
  default void validate() {
    ConnectionArgumentsSupport.INSTANCE.validateForward(getFirst(), getAfter());
  }
}
