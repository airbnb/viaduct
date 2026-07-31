package viaduct.java.api.types;

import viaduct.tenant.runtime.support.ConnectionArgumentsSupport;

/**
 * Arguments for backward pagination through a connection.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.BackwardConnectionArguments}. The math
 * and validation are delegated to the language-neutral {@link ConnectionArgumentsSupport} shared
 * with the Kotlin Tenant API.
 *
 * @see ForwardConnectionArguments
 */
public interface BackwardConnectionArguments extends ConnectionArguments {
  /** Maximum number of items to return from the end; null for the default page size. */
  Integer getLast();

  /** Cursor to start fetching items before (exclusive); null to start from the end. */
  String getBefore();

  /**
   * Returns true if backward pagination needs the total count to resolve the starting offset.
   *
   * <p>This happens whenever {@link #getBefore()} is absent, including the default
   * backward-pagination case where {@link #getLast()} is also absent and the default page size is
   * used. Use {@link #toOffsetLimit(int, int)} instead of {@link #toOffsetLimit(int)}.
   */
  @Override
  default boolean requiresTotalCountForOffsetLimit() {
    return ConnectionArgumentsSupport.INSTANCE.backwardRequiresTotalCount(getBefore());
  }

  /**
   * Converts backward pagination arguments to offset/limit.
   *
   * <ul>
   *   <li>{@link #getLast()} determines the page size (defaults to {@code defaultPageSize}).
   *   <li>{@link #getBefore()} cursor encodes the index of the first item on the next page; the
   *       offset and limit are computed to return the last items ending just before that position,
   *       clamped so the window never extends before index 0.
   *   <li>If {@code before} is absent, a negative offset equal to {@code -pageSize} is returned,
   *       signalling {@link viaduct.java.api.internal.ConnectionBuilder#fromList} to resolve from
   *       the tail of the full list. Prefer {@link #toOffsetLimit(int, int)} with the total count
   *       when available.
   * </ul>
   */
  @Override
  default OffsetLimit toOffsetLimit(int defaultPageSize) {
    ConnectionArgumentsSupport.OffsetBounds b =
        ConnectionArgumentsSupport.INSTANCE.backwardOffsetLimit(
            getLast(), getBefore(), defaultPageSize);
    return new OffsetLimit(b.getOffset(), b.getLimit());
  }

  /**
   * Converts backward pagination arguments to offset/limit when the total count is known.
   *
   * <p>When {@link #getBefore()} is absent, this computes the offset as {@code max(0, totalCount -
   * last)} to return the last N items from the dataset, avoiding the negative-offset signal used by
   * {@link #toOffsetLimit(int)}. When {@code before} is present, {@code totalCount} is ignored and
   * this delegates to {@link #toOffsetLimit(int)}.
   *
   * @param totalCount total number of items in the full dataset
   * @param defaultPageSize default number of items when last not specified
   */
  @Override
  default OffsetLimit toOffsetLimit(int totalCount, int defaultPageSize) {
    ConnectionArgumentsSupport.OffsetBounds b =
        ConnectionArgumentsSupport.INSTANCE.backwardOffsetLimit(
            getLast(), getBefore(), totalCount, defaultPageSize);
    return new OffsetLimit(b.getOffset(), b.getLimit());
  }

  /**
   * Validates backward pagination arguments.
   *
   * @throws IllegalArgumentException if last is not positive or before cursor is invalid
   */
  @Override
  default void validate() {
    ConnectionArgumentsSupport.INSTANCE.validateBackward(getLast(), getBefore());
  }
}
