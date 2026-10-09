package viaduct.tenant.runtime.execution.missingresolver.node;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.tenant.runtime.execution.missingresolver.node.resolverbases.QueryResolvers;

public class JavaMissingNodeResolverContractTest extends MissingNodeResolverContractTest {

  // Provide the field resolver but intentionally NOT the node resolver for Widget.
  @Resolver
  public static class WidgetQueryResolver extends QueryResolvers.Widget {
    @Override
    public CompletableFuture<Widget> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.ref(ctx.globalIDFor(Type.ofClass(Widget.class), ctx.getArguments().getId())));
    }
  }
}
