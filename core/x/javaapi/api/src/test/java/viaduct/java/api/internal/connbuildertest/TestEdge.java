package viaduct.java.api.internal.connbuildertest;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.types.Edge;

/** Edge test GRT with both construction paths (engine + builder), like a generated @edge type. */
final class TestEdge extends ObjectBase implements Edge<TestNode> {
  TestEdge(@Nullable InternalContext ctx, EngineObjectData.Sync data) {
    super(ctx, data);
  }

  TestEdge(@Nullable InternalContext ctx, Map<String, Object> data) {
    super(ctx, data);
  }
}
