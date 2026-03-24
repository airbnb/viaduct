package viaduct.java.api.resolvers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.NodeObject;

/**
 * Base interface for Node resolver implementations in the Java Tenant API.
 *
 * <p>This is the Java equivalent of Kotlin's {@code NodeResolverBase<R>}. Generated node resolver
 * base classes implement this interface, and tenant developers extend those generated classes.
 *
 * <p>Node resolvers handle resolution for types implementing the GraphQL Node interface, which
 * requires a globally unique {@code id} field. These resolvers fetch node data by global ID.
 *
 * <p>Like field resolvers, node resolvers can implement either:
 *
 * <ul>
 *   <li>{@code resolve(Context)} for single-node resolution
 *   <li>{@code batchResolve(List)} for batch resolution (more efficient)
 * </ul>
 *
 * <h2>Single Resolution</h2>
 *
 * <p>Use for simple lookups:
 *
 * <pre>{@code
 * @JavaResolver
 * public class UserNodeResolver extends UserNodeResolverBase {
 *   @Inject UserService userService;
 *
 *   @Override
 *   public CompletableFuture<User> resolve(Context ctx) {
 *     String internalId = ctx.getId().getInternalID();
 *     return userService.fetchById(internalId);
 *   }
 * }
 * }</pre>
 *
 * <h2>Batch Resolution</h2>
 *
 * <p>Use when multiple nodes are frequently fetched together:
 *
 * <pre>{@code
 * @JavaResolver
 * public class UserNodeResolver extends UserNodeResolverBase {
 *   @Inject UserService userService;
 *
 *   @Override
 *   public CompletableFuture<Map<Context, User>> batchResolve(List<Context> contexts) {
 *     List<String> internalIds = contexts.stream()
 *       .map(ctx -> ctx.getId().getInternalID())
 *       .toList();
 *
 *     return userService.fetchByIds(internalIds)
 *       .thenApply(users -> matchContextsToUsers(contexts, users));
 *   }
 * }
 * }</pre>
 *
 * @param <R> the Node type being resolved (must implement NodeObject)
 */
public interface NodeResolverBase<R extends NodeObject> {

  /** Context type alias for node resolvers, providing type-safe access to the node's global ID. */
  interface Context<R extends NodeObject> extends NodeExecutionContext<R> {}

  /**
   * Resolves a single node by its global ID.
   *
   * <p>The engine calls this method when a node is fetched via the {@code node(id: ID!)} field or
   * when following a Node-typed field.
   *
   * <p><strong>Note:</strong> Do not implement both {@code resolve} and {@code batchResolve}.
   * Choose one based on your use case.
   *
   * @param ctx the execution context containing the node's global ID
   * @return a future that completes with the resolved node, or null if not found
   */
  default CompletableFuture<R> resolve(Context<R> ctx) {
    throw new UnsupportedOperationException(
        "Node resolver must implement either resolve() or batchResolve(), but not both");
  }

  /**
   * Resolves multiple nodes by their global IDs in a single batch.
   *
   * <p>The engine calls this method with a batch of node IDs, allowing efficient resolution (e.g.,
   * single database query instead of N queries).
   *
   * <p>The returned map must contain an entry for each input context. For nodes that don't exist,
   * map to null.
   *
   * <p><strong>Note:</strong> Do not implement both {@code resolve} and {@code batchResolve}.
   * Choose one based on your use case.
   *
   * @param contexts list of execution contexts, one per node ID
   * @return a future that completes with a map from context to resolved node (or null if not found)
   */
  default CompletableFuture<Map<Context<R>, R>> batchResolve(List<Context<R>> contexts) {
    throw new UnsupportedOperationException(
        "Node resolver must implement either resolve() or batchResolve(), but not both");
  }

  /**
   * Extracts internal IDs from a list of contexts for batch fetching.
   *
   * <p>Utility method for common batch resolver pattern:
   *
   * <pre>{@code
   * List<String> internalIds = extractInternalIds(contexts);
   * return service.fetchByIds(internalIds).thenApply(...)
   * }</pre>
   *
   * @param contexts the list of node contexts
   * @return list of internal ID strings
   */
  default List<String> extractInternalIds(List<Context<R>> contexts) {
    return contexts.stream().map(ctx -> ctx.getId().getInternalID()).toList();
  }

  /**
   * Creates a map from GlobalID to context for matching batch results.
   *
   * <p>Utility method for batch resolver pattern:
   *
   * <pre>{@code
   * Map<GlobalID<User>, Context> idToContext = createIdToContextMap(contexts);
   * return service.fetchByIds(extractInternalIds(contexts))
   *   .thenApply(users -> users.stream()
   *     .collect(Collectors.toMap(
   *       user -> idToContext.get(globalIDFor(user)),
   *       user -> user
   *     )));
   * }</pre>
   *
   * @param contexts the list of node contexts
   * @return map from global ID to context
   */
  default Map<GlobalID<R>, Context<R>> createIdToContextMap(List<Context<R>> contexts) {
    return contexts.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                NodeExecutionContext::getId, ctx -> ctx, (a, b) -> a));
  }
}
