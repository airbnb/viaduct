package viaduct.java.api.types;

/**
 * Result of converting {@link ConnectionArguments} to an offset/limit pair.
 *
 * <p>This is the bridge between GraphQL pagination arguments ({@code first}, {@code after}, {@code
 * last}, {@code before}) and the offset-based slice that a backend query requires. Produced by
 * {@link ConnectionArguments#toOffsetLimit()} and consumed by {@link
 * viaduct.java.api.internal.ConnectionBuilder} internally.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.OffsetLimit}.
 *
 * @param offset index of the first item to fetch; a negative value signals {@link
 *     viaduct.java.api.internal.ConnectionBuilder#fromList} to resolve from the tail of the dataset
 *     (e.g. {@code -1} is the last item, {@code -10} means the 10th from the end)
 * @param limit maximum number of items to fetch
 */
public record OffsetLimit(int offset, int limit) {}
