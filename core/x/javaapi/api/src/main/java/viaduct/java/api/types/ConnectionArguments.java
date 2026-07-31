package viaduct.java.api.types;

/**
 * Typed access to the pagination inputs ({@code first}, {@code after}, {@code last}, {@code
 * before}) of a connection field.
 *
 * <p>Codegen picks the right sub-interface based on which arguments the schema declares. {@link
 * viaduct.java.api.internal.ConnectionBuilder} calls {@link #toOffsetLimit()} internally — call it
 * directly only when you need the offset/limit to query a backend before using the builder.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.ConnectionArguments}.
 *
 * @see ForwardConnectionArguments
 * @see BackwardConnectionArguments
 * @see MultidirectionalConnectionArguments
 */
public interface ConnectionArguments extends Arguments {
  /** Default page size used when {@code first}/{@code last} are not specified. */
  int DEFAULT_PAGE_SIZE = 20;

  /**
   * Returns true if {@link #toOffsetLimit(int)} requires knowing the total count of items to
   * compute the correct offset.
   *
   * <p>This is the case for backward pagination when only {@link
   * BackwardConnectionArguments#getLast()} is specified (no {@link
   * BackwardConnectionArguments#getBefore()} cursor), because the offset must be computed as {@code
   * totalCount - last}.
   *
   * <p>When this returns true, use {@link #toOffsetLimit(int, int)} with the total count instead of
   * the single-argument overload.
   */
  default boolean requiresTotalCountForOffsetLimit() {
    return false;
  }

  /**
   * Converts connection arguments to offset/limit for database queries.
   *
   * @param defaultPageSize default number of items when first/last not specified
   * @return {@link OffsetLimit} containing the calculated offset and limit
   * @throws IllegalArgumentException for invalid argument combinations or values
   */
  OffsetLimit toOffsetLimit(int defaultPageSize);

  /** Converts connection arguments to offset/limit using the default page size. */
  default OffsetLimit toOffsetLimit() {
    return toOffsetLimit(DEFAULT_PAGE_SIZE);
  }

  /**
   * Converts connection arguments to offset/limit when the total count of items is known.
   *
   * <p>This overload is required when {@link #requiresTotalCountForOffsetLimit()} returns true
   * (i.e. backward pagination with only {@code last} specified). In that case, the offset is
   * computed as {@code max(0, totalCount - last)}.
   *
   * <p>For forward pagination or backward pagination with a {@code before} cursor, {@code
   * totalCount} is ignored and this delegates to {@link #toOffsetLimit(int)}.
   *
   * @param totalCount total number of items in the full dataset
   * @param defaultPageSize default number of items when first/last not specified
   * @return {@link OffsetLimit} containing the calculated offset and limit
   * @throws IllegalArgumentException for invalid argument combinations or values
   */
  default OffsetLimit toOffsetLimit(int totalCount, int defaultPageSize) {
    if (totalCount < 0) {
      throw new IllegalArgumentException("totalCount must be non-negative, got: " + totalCount);
    }
    return toOffsetLimit(defaultPageSize);
  }

  /**
   * Validate connection arguments without converting.
   *
   * @throws IllegalArgumentException if arguments are invalid
   */
  void validate();
}
