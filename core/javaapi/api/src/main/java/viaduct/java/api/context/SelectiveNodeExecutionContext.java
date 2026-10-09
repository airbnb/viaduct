package viaduct.java.api.context;

import viaduct.java.api.types.NodeObject;

/**
 * Extended {@link NodeExecutionContext} for Node resolvers that opt in to selection access.
 *
 * <p>Java equivalent of Kotlin's {@code SelectiveNodeExecutionContext}. Generated resolver bases
 * expose this context only when the type is declared with {@code @resolver(isSelective: true)}.
 *
 * @param <R> the Node type being resolved
 */
public interface SelectiveNodeExecutionContext<R extends NodeObject>
    extends NodeExecutionContext<R> {

  /**
   * Returns the selection set requested for the resolved node.
   *
   * <p>TODO: Return type should be {@code SelectionSet<R>} once that class is implemented in the
   * Java API.
   */
  Object selections();
}
