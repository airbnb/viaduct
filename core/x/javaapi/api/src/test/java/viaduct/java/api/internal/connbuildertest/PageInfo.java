package viaduct.java.api.internal.connbuildertest;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;

/**
 * {@code PageInfo} test GRT. The builder loads it by simple name from the connection's package, so
 * it must be top-level and named {@code PageInfo} in this package.
 */
final class PageInfo extends ObjectBase {
  PageInfo(@Nullable InternalContext ctx, EngineObjectData.Sync data) {
    super(ctx, data);
  }

  PageInfo(@Nullable InternalContext ctx, Map<String, Object> data) {
    super(ctx, data);
  }

  String startCursor() {
    return fetchScalar("startCursor");
  }

  String endCursor() {
    return fetchScalar("endCursor");
  }
}
