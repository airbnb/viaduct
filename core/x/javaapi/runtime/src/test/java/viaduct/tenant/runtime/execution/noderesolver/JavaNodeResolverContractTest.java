package viaduct.tenant.runtime.execution.noderesolver;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.api.testing.TestSchema;
import viaduct.engine.SchemaFactory;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.testing.ResolverTestBase;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.QueryResolvers;

public class JavaNodeResolverContractTest extends NodeResolverContractTest {

  @Test
  void isolatedNodeUsesGeneratedContextTranslation() {
    var schema =
        new SchemaFactory()
            .fromSdl(JavaNodeResolverContractTest.class.getAnnotation(TestSchema.class).value());
    var test = new ResolverTestBase(schema);
    var id = test.globalIDFor(Type.ofClass(NodeObj.class), "tenant1");

    var value =
        test.runNodeResolver(new NodeObjResolver(), new ResolverTestBase.NodeInputs<>(id)).join();

    org.junit.jupiter.api.Assertions.assertEquals("foo", value.getValueOrThrow());
  }

  // --- Resolvers ---

  @Resolver
  public static class QueryNodeObjResolver extends QueryResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(QueryResolvers.NodeObj.Context ctx) {
      String internalId = ctx.getArguments().getId();
      GlobalID<NodeObj> id = ctx.globalIDFor(Type.ofClass(NodeObj.class), internalId);
      return CompletableFuture.completedFuture(
          NodeObj.builder(ctx).id(id).value(internalId).build());
    }
  }

  @Resolver
  public static class NodeReferenceResolver extends QueryResolvers.NodeReference {
    @Override
    public CompletableFuture<NodeObj> resolve(QueryResolvers.NodeReference.Context ctx) {
      String internalId = ctx.getArguments().getId();
      return CompletableFuture.completedFuture(
          ctx.ref(ctx.globalIDFor(Type.ofClass(NodeObj.class), internalId)));
    }
  }

  @Resolver
  public static class ObjectWithNodeFieldResolver extends QueryResolvers.ObjectWithNodeField {
    @Override
    public CompletableFuture<ObjectWithNodeField> resolve(
        QueryResolvers.ObjectWithNodeField.Context ctx) {
      NodeObj node = ctx.ref(ctx.globalIDFor(Type.ofClass(NodeObj.class), "nestedNode"));
      return CompletableFuture.completedFuture(ObjectWithNodeField.builder(ctx).node(node).build());
    }
  }

  @Resolver
  public static class NodeObjResolver extends NodeResolvers.NodeObj {
    @Override
    public CompletableFuture<NodeObj> resolve(NodeResolvers.NodeObj.Context ctx) {
      return CompletableFuture.completedFuture(NodeObj.builder(ctx).value("foo").build());
    }
  }

  @Resolver
  public static class NodeRefWithIllegalAccessResolver
      extends QueryResolvers.NodeRefWithIllegalAccess {
    @Override
    public CompletableFuture<NodeObj> resolve(QueryResolvers.NodeRefWithIllegalAccess.Context ctx) {
      NodeObj ref = ctx.ref(ctx.globalIDFor(Type.ofClass(NodeObj.class), "1"));
      ref.getIdOrThrow(); // valid — id can always be read
      ref.getValueOrThrow(); // illegal — must throw
      return CompletableFuture.completedFuture(ref);
    }
  }
}
