package viaduct.java.api.types;

/**
 * A paginated list of {@link Edge}s plus a {@code pageInfo} object describing page boundaries.
 *
 * <p>Declare the type in your {@code .graphqls} schema — codegen generates the implementation and a
 * builder extending {@link viaduct.java.api.internal.ConnectionBuilder} with {@code fromSlice},
 * {@code fromList}, and {@code fromEdges}.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.Connection}.
 *
 * @param <E> the edge type wrapping nodes of type {@code N} and carrying a per-item cursor
 * @param <N> the node type returned by each edge
 * @see Edge
 * @see viaduct.java.api.internal.ConnectionBuilder
 */
public interface Connection<E extends Edge<N>, N> extends GraphQLObject {}
