package viaduct.tenant.runtime.execution.noderesolver;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.QueryResolvers;

public class JavaNodeResolverContractTest extends NodeResolverContractTest {

  // --- Resolvers ---

  @Resolver
  public static class QueryNodeObjResolver extends QueryResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      String internalId = ctx.getArguments().getId();
      GlobalID<NodeObj> id = ctx.globalIDFor(Type.ofClass(NodeObj.class), internalId);
      return CompletableFuture.completedFuture(
          NodeObj.builder(ctx).id(id).value(internalId).build());
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
      return CompletableFuture.completedFuture(ObjectWithNodeField.builder(ctx).node(node).build());
    }
  }

  @Resolver
  public static class NodeObjResolver extends NodeResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      return CompletableFuture.completedFuture(NodeObj.builder(ctx).value("foo").build());
    }
  }

  @Resolver
  public static class NodeRefWithIllegalAccessResolver
      extends QueryResolvers.NodeRefWithIllegalAccess {
    @Override
    public CompletableFuture<NodeObj> resolve(Context ctx) {
      NodeObj ref = ctx.nodeRef(ctx.globalIDFor(Type.ofClass(NodeObj.class), "1"));
      ref.getId(); // valid — id can always be read
      ref.getValue(); // illegal — must throw
      return CompletableFuture.completedFuture(ref);
    }
  }
}
