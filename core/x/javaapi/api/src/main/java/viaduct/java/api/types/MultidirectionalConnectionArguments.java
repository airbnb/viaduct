package viaduct.java.api.types;

import viaduct.tenant.runtime.support.ConnectionArgumentsSupport;

/**
 * Arguments for connections that expose all four pagination args ({@code first}, {@code after},
 * {@code last}, {@code before}).
 *
 * <p>Forward args take precedence over backward in {@link #toOffsetLimit(int)}; passing neither
 * returns the first page at the default size. Forward and backward args cannot be mixed in the same
 * request.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.MultidirectionalConnectionArguments}. The
 * direction dispatch, math, and validation are delegated to the language-neutral {@link
 * ConnectionArgumentsSupport} shared with the Kotlin Tenant API (which validates exactly once).
 *
 * <p>Prefer {@link ForwardConnectionArguments} or {@link BackwardConnectionArguments} when only one
 * direction is needed.
 */
public interface MultidirectionalConnectionArguments
    extends ForwardConnectionArguments, BackwardConnectionArguments {

  /**
   * Returns true if backward pagination is active ({@code last}/{@code before}) and requires total
   * count. Forward pagination never requires total count; backward pagination requires it only when
   * the {@code before} cursor is absent.
   */
  @Override
  default boolean requiresTotalCountForOffsetLimit() {
    return ConnectionArgumentsSupport.INSTANCE.multidirectionalRequiresTotalCount(
        getFirst(), getAfter(), getLast(), getBefore());
  }

  /**
   * Converts multidirectional pagination arguments to offset/limit. Uses forward pagination ({@code
   * first}/{@code after}) if provided, otherwise falls back to backward pagination ({@code
   * last}/{@code before}).
   */
  @Override
  default OffsetLimit toOffsetLimit(int defaultPageSize) {
    ConnectionArgumentsSupport.OffsetBounds b =
        ConnectionArgumentsSupport.INSTANCE.multidirectionalOffsetLimit(
            getFirst(), getAfter(), getLast(), getBefore(), defaultPageSize);
    return new OffsetLimit(b.getOffset(), b.getLimit());
  }

  /**
   * Converts multidirectional pagination arguments to offset/limit when total count is known. Uses
   * forward pagination ({@code first}/{@code after}) if provided, otherwise falls back to backward
   * pagination ({@code last}/{@code before}).
   */
  @Override
  default OffsetLimit toOffsetLimit(int totalCount, int defaultPageSize) {
    ConnectionArgumentsSupport.OffsetBounds b =
        ConnectionArgumentsSupport.INSTANCE.multidirectionalOffsetLimit(
            getFirst(), getAfter(), getLast(), getBefore(), totalCount, defaultPageSize);
    return new OffsetLimit(b.getOffset(), b.getLimit());
  }

  /**
   * Validates multidirectional pagination arguments.
   *
   * @throws IllegalArgumentException if mixing forward and backward pagination, or if individual
   *     arguments are invalid
   */
  @Override
  default void validate() {
    ConnectionArgumentsSupport.INSTANCE.validateMultidirectional(
        getFirst(), getAfter(), getLast(), getBefore());
  }
}
