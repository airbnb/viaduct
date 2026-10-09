package viaduct.java.api.context;

import viaduct.java.api.types.CompositeOutput;

/**
 * Marker interface for field resolver contexts that can access the requested selection set.
 *
 * <p>Generated resolver Context wrappers implement this interface only when the GraphQL field is
 * declared with {@code @resolver(isSelective: true)}.
 *
 * @param <O> the composite output type of the resolved field
 */
public interface SelectiveFieldExecutionContext<O extends CompositeOutput> {

  /**
   * Returns the selection set requested for the resolved field.
   *
   * <p>TODO: Return type should be SelectionSet&lt;O&gt; once that class is implemented in the Java
   * API.
   */
  Object getSelections();
}
