package viaduct.java.api.resolvers;

import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.types.NodeObject;

/**
 * Base interface for Node resolver implementations in the Java Tenant API.
 *
 * <p>This is the Java equivalent of Kotlin's {@code NodeResolverBase<R>}. Generated node resolver
 * base classes implement this interface, and tenant developers extend those generated classes.
 *
 * @param <R> the Node type being resolved (must implement NodeObject)
 */
public interface NodeResolverBase<R extends NodeObject> {

  /** Context type alias for node resolvers, providing type-safe access to the node's global ID. */
  interface Context<R extends NodeObject> extends NodeExecutionContext<R> {}
}
