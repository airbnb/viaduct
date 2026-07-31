package viaduct.java.api.types;

/**
 * A wrapper around a node that also carries a {@code cursor} encoding the item's position in the
 * list. Clients pass the cursor back as {@code after}/{@code before} to resume pagination from that
 * point.
 *
 * <p>{@link viaduct.java.api.internal.ConnectionBuilder#fromSlice} and {@link
 * viaduct.java.api.internal.ConnectionBuilder#fromList} set {@code node} and {@code cursor}
 * automatically. Use {@link viaduct.java.api.internal.ConnectionBuilder#fromEdges} when your edge
 * type has additional custom fields.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.types.Edge}.
 *
 * @param <N> the type of node this edge contains
 * @see Connection
 */
public interface Edge<N> extends GraphQLObject {}
