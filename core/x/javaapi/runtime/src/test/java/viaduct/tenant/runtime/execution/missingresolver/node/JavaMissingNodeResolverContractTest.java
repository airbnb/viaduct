package viaduct.tenant.runtime.execution.missingresolver.node;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.testing.JavaResolverImplementationValidator;
import viaduct.tenant.runtime.execution.missingresolver.node.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.missingresolver.node.resolverbases.QueryResolvers;

public class JavaMissingNodeResolverContractTest extends MissingNodeResolverContractTest {

  @Override
  protected void onBeforeBuild() {
    JavaResolverImplementationValidator.validate(
        getClass(), QueryResolvers.Widget.class, NodeResolvers.Widget.class);
  }

  // Provide the field resolver but intentionally NOT the node resolver for Widget.
  @Resolver
  public static class WidgetQueryResolver extends QueryResolvers.Widget {
    @Override
    public CompletableFuture<Widget> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          ctx.nodeRef(ctx.globalIDFor(Type.ofClass(Widget.class), ctx.getArguments().getId())));
    }
  }
}
