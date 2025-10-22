package viaduct.java.api.context;

import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.NodeObject;

/**
 * An {@link ExecutionContext} provided to Node resolvers.
 *
 * @param <T> The type of the Node being resolved
 */
public interface NodeExecutionContext<T extends NodeObject> extends ResolverExecutionContext {
  /**
   * The ID of the node that is being resolved.
   *
   * @return The global ID of the node being resolved
   */
  GlobalID<T> getId();
}
