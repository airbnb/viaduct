package viaduct.tenant.runtime.execution.defaultnoderesolver;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.tenant.runtime.execution.defaultnoderesolver.resolverbases.NodeResolvers;

public class JavaDefaultQueryNodeResolverContractTest extends DefaultQueryNodeResolverContractTest {

  // --- Resolvers ---

  @Resolver
  public static class TestUserNodeResolver extends NodeResolvers.TestUser {
    @Override
    public CompletableFuture<TestUser> resolve(Context ctx) {
      String id = ctx.globalIDStringFor(Type.ofClass(TestUser.class), ctx.getId().getInternalID());
      return CompletableFuture.completedFuture(
          TestUser.builder(ctx).id(id).name("user name").build());
    }
  }
}
