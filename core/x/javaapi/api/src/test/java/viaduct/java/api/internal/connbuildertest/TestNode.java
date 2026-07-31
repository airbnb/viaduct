package viaduct.java.api.internal.connbuildertest;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;

/** Minimal node test GRT. */
final class TestNode extends ObjectBase {
  TestNode(@Nullable InternalContext ctx, Map<String, Object> data) {
    super(ctx, data);
  }
}
