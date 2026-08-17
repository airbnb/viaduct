package viaduct.java.api.internal.connbuildertest;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.ConnectionBuilder;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.types.Connection;

/** Connection test GRT with the (InternalContext, Map) ctor the builder invokes reflectively. */
final class TestConnection extends ObjectBase implements Connection<TestEdge, TestNode> {
  TestConnection(@Nullable InternalContext ctx, EngineObjectData.Sync data) {
    super(ctx, data);
  }

  TestConnection(@Nullable InternalContext ctx, Map<String, Object> data) {
    super(ctx, data);
  }

  List<TestEdge> edges() {
    return fetchObjectList("edges", TestEdge::new);
  }

  PageInfo pageInfo() {
    return fetchObject("pageInfo", PageInfo::new);
  }

  /** Minimal concrete builder for exercising the shared pagination behavior. */
  static final class Builder extends ConnectionBuilder<TestConnection, TestEdge, TestNode> {
    Builder(ExecutionContext ctx) {
      super(ctx, TestConnection.class, TestEdge.class);
    }

    Builder ownerIDs(List<? extends GlobalID<?>> ownerIDs) {
      putGlobalIDListField("ownerIDs", ownerIDs);
      return this;
    }
  }
}
