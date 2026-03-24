package viaduct.java.api.globalid;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * GlobalIDs are objects in Viaduct that contain 'type' and 'internalID' properties. They are used
 * to uniquely identify node objects in the graph.
 *
 * <p>GlobalID values support structural equality, as opposed to referential equality.
 *
 * <p>A GlobalID&lt;T&gt; will be generated for fields with the @idOf(type:"T") directive.
 *
 * <p>Instances of GlobalID can be created using execution-context objects, e.g.,
 * ExecutionContext.nodeIDFor(User.class, "123").
 *
 * @param <T> The type of NodeCompositeOutput this GlobalID refers to
 */
public interface GlobalID<T extends NodeCompositeOutput> {
  /**
   * @return The type of the node object, e.g. User.
   */
  Type<T> getType();

  /**
   * @return The internal ID of the node object, e.g. "123".
   */
  String getInternalID();
}
