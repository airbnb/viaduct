package viaduct.tenant.runtime.execution.noderesolver;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.java.runtime.bridge.ModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.QueryResolvers;

public class JavaNodeResolverContractTest extends NodeResolverContractTest {

  private final DefaultResolverClassFinder classFinder =
      new DefaultResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final ModuleBootstrapper bootstrapper =
      new ModuleBootstrapper(classFinder, CodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>
      createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
  }

  // --- Resolvers ---

  @Resolver
  public static class QueryNodeObjResolver extends QueryResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      String internalId = ctx.getArguments().getId();
      String id = ctx.globalIDStringFor(Type.ofClass(NodeObj.class), internalId);
      return CompletableFuture.completedFuture(NodeObj.builder().id(id).value(internalId).build());
    }
  }

  @Resolver
  public static class NodeReferenceResolver extends QueryResolvers.NodeReference {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      String internalId = ctx.getArguments().getId();
      return CompletableFuture.completedFuture(
          ctx.nodeRef(ctx.globalIDFor(Type.ofClass(NodeObj.class), internalId)));
    }
  }

  @Resolver
  public static class ObjectWithNodeFieldResolver extends QueryResolvers.ObjectWithNodeField {
    @Override
    public CompletableFuture<ObjectWithNodeField> resolve(Context ctx) {
      NodeObj node = ctx.nodeRef(ctx.globalIDFor(Type.ofClass(NodeObj.class), "nestedNode"));
      return CompletableFuture.completedFuture(ObjectWithNodeField.builder().node(node).build());
    }
  }

  @Resolver
  public static class NodeObjResolver extends NodeResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      return CompletableFuture.completedFuture(NodeObj.builder().value("foo").build());
    }
  }
}
