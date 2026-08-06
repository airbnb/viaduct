package viaduct.java.api.internal;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.java.api.types.NodeObject;

/**
 * Base class for Java GRTs that implement the GraphQL Node interface.
 *
 * <p>Extends {@link ObjectBase} and implements {@link NodeObject}, satisfying the type bound {@code
 * R extends NodeObject} required by {@link viaduct.java.api.resolvers.NodeResolverBase}.
 */
public abstract class NodeObjectBase extends ObjectBase implements NodeObject {

  protected NodeObjectBase(@Nullable InternalContext __context, EngineObjectData.Sync data) {
    super(__context, data);
  }

  protected NodeObjectBase(@Nullable InternalContext __context, Map<String, Object> data) {
    super(__context, data);
  }

  protected NodeObjectBase(@Nullable InternalContext __context, NodeReference nodeReference) {
    super(__context, nodeReference);
  }

  protected NodeObjectBase(
      @Nullable InternalContext __context, RootFieldReference rootFieldReference) {
    super(__context, rootFieldReference);
  }
}
