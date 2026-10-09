package viaduct.java.api.globalid;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * GlobalIDs are objects in Viaduct that contain 'type' and 'internalID' properties. They are used
 * to uniquely identify node objects in the graph.
 *
 * <p>Framework-created GlobalID values compare structurally by type and internal ID. Type identity
 * includes the GraphQL name and Java Class, so a serialized ID round trip requires the same GRT
 * class.
 *
 * <p>A GlobalID&lt;T&gt; will be generated for fields with the @idOf(type:"T") directive.
 *
 * <p>Instances of GlobalID can be created using execution-context objects, e.g.,
 * ExecutionContext.nodeIDFor(User.class, "123").
 *
 * @param <T> The type of NodeCompositeOutput this GlobalID refers to
 */
public interface GlobalID<T extends NodeCompositeOutput> {
  /** Returns the type of the node object, e.g. User. */
  Type<T> getType();

  /** Returns the internal ID of the node object, e.g. "123". */
  String getInternalID();
}
