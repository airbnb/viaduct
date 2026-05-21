package viaduct.java.api.internal;

import java.util.Map;
import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.java.api.types.NodeObject;

/**
 * Base class for Java GRTs that implement the GraphQL Node interface.
 *
 * <p>Extends {@link ObjectBase} and implements {@link NodeObject}, satisfying the type bound {@code
 * R extends NodeObject} required by {@link viaduct.java.api.resolvers.NodeResolverBase}.
 */
public abstract class NodeObjectBase extends ObjectBase implements NodeObject {

  protected NodeObjectBase(EngineObjectData.Sync data) {
    super(data);
  }

  protected NodeObjectBase(Map<String, Object> data) {
    super(data);
  }

  protected NodeObjectBase(NodeReference nodeReference) {
    super(nodeReference);
  }
}
