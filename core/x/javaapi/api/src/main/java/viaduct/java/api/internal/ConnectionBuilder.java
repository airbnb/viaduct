package viaduct.java.api.internal;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import viaduct.errors.HandleErrors;
import viaduct.java.api.context.ConnectionFieldExecutionContext;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.Connection;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.Edge;
import viaduct.java.api.types.OffsetCursor;
import viaduct.java.api.types.OffsetLimit;

/**
 * Base builder for Connection types with pagination utilities.
 *
 * <p>Generated Connection builders extend this class to gain {@link #fromEdges}, {@link
 * #fromSlice}, and {@link #fromList}.
 *
 * <p>Java equivalent of Kotlin's {@code viaduct.api.internal.ConnectionBuilder}, kept close to it
 * in spirit: the builder holds the per-request {@link ExecutionContext} and constructs the edge,
 * {@code PageInfo}, and connection GRTs itself. Pagination methods that derive bounds from request
 * arguments require a {@link ConnectionFieldExecutionContext}; other operations remain available to
 * unpaged connection resolvers. Because Java GRTs are map-backed (no {@code ObjectBase.Builder}
 * base and no reflective {@code Type} accessors), it builds each GRT by populating its backing
 * {@code Map} and invoking the generated {@code (InternalContext, Map)} constructor reflectively —
 * the analog of Kotlin building an {@code EngineObjectData} map and wrapping it. The generated
 * subclass supplies only the concrete connection and edge {@code Class} handles.
 *
 * <p>Composable, mirroring Kotlin's connection builder: the generated subclass adds per-field
 * setters (for connection fields beyond {@code edges}/{@code pageInfo}, such as {@code
 * totalCount}), a pagination method ({@link #fromEdges}/{@link #fromSlice}/{@link #fromList})
 * populates {@code edges} and {@code pageInfo}, and {@link #build()} finalizes the connection GRT.
 * Field setters and a pagination method can be combined in any order before {@code build()}.
 *
 * @param <C> the concrete Connection type being built (must implement {@code Connection<E, N>})
 * @param <E> the Edge type (must implement {@code Edge<N>})
 * @param <N> the Node type contained in edges
 */
public abstract class ConnectionBuilder<C extends Connection<E, N>, E extends Edge<N>, N> {
  private static final String PAGE_INFO_SIMPLE_NAME = "PageInfo";

  private final ExecutionContext context;
  private final InternalContext internalContext;
  private final Class<C> connectionClass;
  private final Class<E> edgeClass;
  private final Map<String, Object> data = new LinkedHashMap<>();

  protected ConnectionBuilder(
      ExecutionContext context, Class<C> connectionClass, Class<E> edgeClass) {
    this.context = context;
    this.internalContext = InternalContext.from(context);
    this.connectionClass = connectionClass;
    this.edgeClass = edgeClass;
  }

  /**
   * Stores a connection field value in the backing map. Called by the generated per-field setters
   * so additional connection fields (e.g. {@code totalCount}) can be populated alongside the
   * pagination-produced {@code edges}/{@code pageInfo}. Mirrors the generated regular-object
   * builder's {@code data.put}.
   */
  protected void putField(String fieldName, Object value) {
    data.put(fieldName, checkField(fieldName, value));
  }

  /** Validates and stores a generated connection GlobalID setter value in wire format. */
  protected final void putGlobalIDField(String fieldName, GlobalID<?> value) {
    GlobalID<?> checkedValue = checkField(fieldName, value);
    data.put(
        fieldName,
        checkedValue == null
            ? null
            : internalContext
                .getGlobalIDCodec()
                .serialize(checkedValue.getType().getName(), checkedValue.getInternalID()));
  }

  /** Validates and stores a generated connection GlobalID-list setter value in wire format. */
  protected final void putGlobalIDListField(String fieldName, List<? extends GlobalID<?>> value) {
    List<? extends GlobalID<?>> checkedValue = checkField(fieldName, value);
    data.put(
        fieldName,
        checkedValue == null
            ? null
            : checkedValue.stream()
                .map(
                    id ->
                        id == null
                            ? null
                            : internalContext
                                .getGlobalIDCodec()
                                .serialize(id.getType().getName(), id.getInternalID()))
                .toList());
  }

  private <T> T checkField(String fieldName, T value) {
    return OutputBuilderTypeChecker.checkField(
        internalContext, connectionClass.getSimpleName(), fieldName, value);
  }

  /** Finalizes and returns the connection GRT from the accumulated fields. */
  public C build() {
    return construct(connectionClass, new LinkedHashMap<>(data));
  }

  /**
   * The pagination arguments from the current GraphQL request. Available for resolvers that need
   * the offset/limit before calling a builder method; {@link #fromSlice} and {@link #fromList} read
   * these internally.
   */
  protected ConnectionArguments arguments() {
    if (context instanceof ConnectionFieldExecutionContext<?, ?, ?, ?> connectionContext) {
      return connectionContext.getArguments();
    }
    throw new IllegalStateException(
        "Connection pagination requires a ConnectionFieldExecutionContext with"
            + " ConnectionArguments; this builder was created with "
            + context.getClass().getName());
  }

  /**
   * Populate {@code edges}/{@code pageInfo} from pre-constructed edges, with no next/previous page.
   */
  public ConnectionBuilder<C, E, N> fromEdges(List<E> edges) {
    return fromEdges(edges, false, false);
  }

  /**
   * Populate {@code edges}/{@code pageInfo} from pre-constructed edges.
   *
   * <p>Use this when your backend returns opaque cursors directly, or when your edge type has
   * custom fields beyond {@code node} and {@code cursor} (e.g. a {@code score} on a search edge).
   * You are responsible for setting {@code cursor} on each edge and for supplying {@code
   * hasNextPage}/{@code hasPreviousPage}. {@code pageInfo.startCursor}/{@code endCursor} are
   * extracted automatically from the first and last edges.
   *
   * @param edges pre-constructed edges; each must have its {@code cursor} field set
   * @param hasNextPage whether more items exist after this page
   * @param hasPreviousPage whether more items exist before this page
   * @return this builder, for chaining with field setters and {@link #build()}
   */
  public ConnectionBuilder<C, E, N> fromEdges(
      List<E> edges, boolean hasNextPage, boolean hasPreviousPage) {
    return HandleErrors.framework(
        "ConnectionBuilder.fromEdges",
        () -> {
          List<E> checkedEdges = checkField("edges", edges);
          String startCursor = checkedEdges.isEmpty() ? null : cursorOf(checkedEdges.get(0));
          String endCursor =
              checkedEdges.isEmpty() ? null : cursorOf(checkedEdges.get(checkedEdges.size() - 1));
          Object pageInfo = buildPageInfo(hasNextPage, hasPreviousPage, startCursor, endCursor);
          Object checkedPageInfo = checkField("pageInfo", pageInfo);
          data.put("edges", checkedEdges);
          data.put("pageInfo", checkedPageInfo);
          return this;
        });
  }

  /**
   * Populate {@code edges}/{@code pageInfo} from a backend-paginated slice of items.
   *
   * <p>Use this when your backend handles pagination (e.g. a SQL query with {@code LIMIT}/{@code
   * OFFSET}). You fetch the right slice and supply {@code hasNextPage}. Cursors are encoded
   * automatically per item, and {@code hasPreviousPage} is set to {@code true} when {@code offset >
   * 0}.
   *
   * <p>This overload derives the offset/limit from the request arguments via {@link
   * ConnectionArguments#toOffsetLimit()}. It therefore cannot serve backward pagination that only
   * specifies {@code last} (no {@code before}), where the offset can only be computed from the
   * backend's total item count: use {@link #fromSlice(List, OffsetLimit, boolean, Function)} with
   * an explicit {@link OffsetLimit} in that case (the same one you resolved via {@link
   * ConnectionArguments#toOffsetLimit(int)} to fetch the slice). This method fails fast rather than
   * silently encoding the negative tail-relative offset that {@link
   * ConnectionArguments#toOffsetLimit()} returns for that shape.
   *
   * @param items items to paginate; may contain one extra item for next-page detection
   * @param hasNextPage whether more items exist after this slice
   * @param buildNode converts each item to the edge's node value; return {@code null} to omit
   * @return this builder, for chaining with field setters and {@link #build()}
   * @throws IllegalArgumentException if the arguments require a total count to resolve the offset
   *     (see {@link ConnectionArguments#requiresTotalCountForOffsetLimit()})
   */
  public <I> ConnectionBuilder<C, E, N> fromSlice(
      List<I> items, boolean hasNextPage, Function<I, N> buildNode) {
    if (arguments().requiresTotalCountForOffsetLimit()) {
      throw new IllegalArgumentException(
          "fromSlice(items, hasNextPage, buildNode) cannot resolve the offset for backward"
              + " pagination with only `last` (no `before`): the offset depends on the backend's"
              + " total item count. Resolve the offset with arguments.toOffsetLimit(totalCount) and"
              + " pass it to fromSlice(items, offsetLimit, hasNextPage, buildNode).");
    }
    OffsetLimit offsetLimit = arguments().toOffsetLimit();
    return buildEdges(items, hasNextPage, offsetLimit.offset(), offsetLimit.limit(), buildNode);
  }

  /**
   * Populate {@code edges}/{@code pageInfo} from a backend-paginated slice of items, using an
   * {@link OffsetLimit} you already resolved to fetch that slice.
   *
   * <p>This is the counterpart to {@link #fromSlice(List, boolean, Function)} for pagination shapes
   * whose offset depends on the backend's total item count — backward pagination with only {@code
   * last} (no {@code before}). Resolve the bounds once and reuse them for both the fetch and the
   * cursor/{@code PageInfo} construction:
   *
   * <pre>{@code
   * OffsetLimit ol = ctx.getArguments().requiresTotalCountForOffsetLimit()
   *     ? ctx.getArguments().toOffsetLimit(repo.count())
   *     : ctx.getArguments().toOffsetLimit();
   * List<Post> fetched = repo.fetchPosts(ol.offset(), ol.limit() + 1);
   * return PostsConnection.builder(ctx)
   *     .fromSlice(fetched, ol, fetched.size() > ol.limit(), post -> …)
   *     .build();
   * }</pre>
   *
   * <p>{@link OffsetCursor}s are encoded automatically per item starting from {@link
   * OffsetLimit#offset()}, and {@code hasPreviousPage} is set to {@code true} when that offset is
   * {@code > 0}.
   *
   * @param items items to paginate; may contain one extra item for next-page detection
   * @param offsetLimit the resolved offset/limit used to fetch {@code items}; must have a
   *     non-negative {@link OffsetLimit#offset()} (the tail-relative signal from the no-count
   *     overload is not accepted)
   * @param hasNextPage whether more items exist after this slice
   * @param buildNode converts each item to the edge's node value; return {@code null} to omit
   * @return this builder, for chaining with field setters and {@link #build()}
   */
  public <I> ConnectionBuilder<C, E, N> fromSlice(
      List<I> items, OffsetLimit offsetLimit, boolean hasNextPage, Function<I, N> buildNode) {
    if (offsetLimit.offset() < 0) {
      throw new IllegalArgumentException(
          "fromSlice requires a resolved, non-negative offset, got: "
              + offsetLimit.offset()
              + ". For backward pagination with only `last`, resolve it via"
              + " arguments.toOffsetLimit(totalCount) before fetching the slice.");
    }
    return buildEdges(items, hasNextPage, offsetLimit.offset(), offsetLimit.limit(), buildNode);
  }

  /**
   * Populate {@code edges}/{@code pageInfo} from a complete, unpaginated list.
   *
   * <p>Use this when your backend returns the full dataset and Viaduct should handle slicing. For
   * large datasets prefer {@link #fromSlice} with a backend-paginated query. Pagination arguments
   * are derived from the execution context: forward ({@code first}/{@code after}) slices from the
   * head; backward ({@code last} without {@code before}) slices from the tail. Cursors are encoded
   * automatically and {@code hasNextPage}/{@code hasPreviousPage} are set based on position within
   * the full list.
   *
   * @param items the complete, unpaginated list of items
   * @param buildNode converts each item to the edge's node value; return {@code null} to omit
   * @return this builder, for chaining with field setters and {@link #build()}
   */
  public <I> ConnectionBuilder<C, E, N> fromList(List<I> items, Function<I, N> buildNode) {
    OffsetLimit offsetLimit = arguments().toOffsetLimit();
    int offset =
        offsetLimit.offset() < 0
            ? Math.max(0, items.size() + offsetLimit.offset())
            : offsetLimit.offset();
    int limit = offsetLimit.limit();
    List<I> slice = subList(items, offset, limit);
    boolean hasNextPage = (long) offset + (long) limit < items.size();
    return buildEdges(slice, hasNextPage, offset, limit, buildNode);
  }

  private <I> ConnectionBuilder<C, E, N> buildEdges(
      List<I> items, boolean hasNextPage, int offset, int limit, Function<I, N> buildNode) {
    List<E> edges = new ArrayList<>();
    int count = Math.min(limit, items.size());
    for (int idx = 0; idx < count; idx++) {
      N node = buildNode.apply(items.get(idx));
      // Documented contract: a null mapper result omits the item — no edge is created. The cursor
      // of each surviving edge stays anchored to its original position (offset + idx), so
      // after/before cursors still resume pagination correctly against the source list.
      if (node == null) {
        continue;
      }
      Map<String, Object> edgeData = new LinkedHashMap<>();
      edgeData.put("node", node);
      edgeData.put("cursor", OffsetCursor.fromOffset(offset + idx).getValue());
      edges.add(construct(edgeClass, edgeData));
    }
    return fromEdges(edges, hasNextPage, offset > 0);
  }

  /**
   * Reads the {@code cursor} field off an edge GRT, independent of its backing representation.
   * {@link ObjectBase#fetchScalar} reads through the same path the generated {@code getCursor()}
   * getter uses, so this works for both builder-backed (map) edges and pre-resolved engine-backed
   * edges — the latter of which would NPE if we assumed the builder's map representation.
   */
  private String cursorOf(E edge) {
    String cursor = ((ObjectBase) edge).fetchScalar("cursor");
    if (cursor == null) {
      throw new IllegalArgumentException("Edge is missing its required 'cursor' field: " + edge);
    }
    return cursor;
  }

  /** Builds the connection's {@code PageInfo} GRT (same package as the connection type). */
  private Object buildPageInfo(
      boolean hasNextPage, boolean hasPreviousPage, String startCursor, String endCursor) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("hasNextPage", hasNextPage);
    data.put("hasPreviousPage", hasPreviousPage);
    data.put("startCursor", startCursor);
    data.put("endCursor", endCursor);
    Class<?> pageInfoClass;
    try {
      pageInfoClass =
          connectionClass
              .getClassLoader()
              .loadClass(connectionClass.getPackageName() + "." + PAGE_INFO_SIMPLE_NAME);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "PageInfo GRT not found for connection " + connectionClass, e);
    }
    return construct(pageInfoClass, data);
  }

  /**
   * Constructs a map-backed GRT by invoking its generated {@code (InternalContext, Map)}
   * constructor. This is the reflective analog of Kotlin wrapping an {@code EngineObjectData} map.
   */
  private <T> T construct(Class<T> grtClass, Map<String, Object> data) {
    try {
      Constructor<T> ctor = grtClass.getDeclaredConstructor(InternalContext.class, Map.class);
      ctor.setAccessible(true);
      return ctor.newInstance(internalContext, data);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to construct GRT " + grtClass.getName() + " from a connection builder", e);
    }
  }

  /** {@code items.drop(offset).take(limit)}, clamped to the list bounds. */
  private static <I> List<I> subList(List<I> items, int offset, int limit) {
    if (offset >= items.size() || limit <= 0) {
      return List.of();
    }
    int from = Math.max(0, offset);
    int to = (int) Math.min((long) items.size(), (long) from + limit);
    return items.subList(from, to);
  }
}
