package viaduct.tenant.runtime.execution.idof;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.tenant.runtime.execution.idof.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.idof.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.idof.resolverbases.UserResolvers;

public class JavaIdOfContractTest extends IdOfContractTest {

  // --- Resolvers ---

  @Resolver
  public static class UserNodeResolver extends NodeResolvers.User {
    @Override
    public CompletableFuture<User> resolve(NodeResolvers.User.Context ctx) {
      String internal = ctx.getId().getInternalID();
      GlobalID<User> aliceGlobalId = ctx.globalIDFor(Type.ofClass(User.class), "alice@yahoo.com");
      GlobalID<User> bobGlobalId = ctx.globalIDFor(Type.ofClass(User.class), "bob@hotmail.com");
      User result;
      if ("alice@yahoo.com".equals(internal)) {
        result = User.builder(ctx).id(aliceGlobalId).name("Alice").cohostID(bobGlobalId).build();
      } else if ("bob@hotmail.com".equals(internal)) {
        result = User.builder(ctx).id(bobGlobalId).name("Bob").cohostID(aliceGlobalId).build();
      } else {
        throw new IllegalArgumentException("No User with id=" + internal);
      }
      return CompletableFuture.completedFuture(result);
    }
  }

  /** User.cohost: tests consumption from object field (cohostID is now typed GlobalID<User>). */
  @Resolver(objectValueFragment = "cohostID")
  public static class UserCohostResolver extends UserResolvers.Cohost {
    @Override
    public CompletableFuture<User> resolve(UserResolvers.Cohost.Context ctx) {
      GlobalID<User> cohostId = ctx.getObjectValue().getCohostIDOrThrow();
      return CompletableFuture.completedFuture(ctx.nodeRef(cohostId));
    }
  }

  /** Query.userFromInput: tests consumption from input field (now typed GlobalID<User>). */
  @Resolver
  public static class QueryUserFromInputResolver extends QueryResolvers.UserFromInput {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.UserFromInput.Context ctx) {
      GlobalID<User> inputId = ctx.getArguments().getId().getId();
      return CompletableFuture.completedFuture(ctx.nodeRef(inputId));
    }
  }

  /** Query.userFromArgument: tests consumption from field argument (now typed GlobalID<User>). */
  @Resolver
  public static class QueryUserFromArgumentResolver extends QueryResolvers.UserFromArgument {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.UserFromArgument.Context ctx) {
      GlobalID<User> id = ctx.getArguments().getId();
      return CompletableFuture.completedFuture(User.builder(ctx).id(id).name("Alice").build());
    }
  }

  /** Query.entityFromID: tests polymorphic aspects of ids. */
  @Resolver
  public static class QueryEntityFromIDResolver extends QueryResolvers.EntityFromID {
    @Override
    public CompletableFuture<Entity> resolve(QueryResolvers.EntityFromID.Context ctx) {
      GlobalID<Entity> id = ctx.getArguments().getId();
      String typeName = id.getType().getName();
      if (!"User".equals(typeName) && !"BadEntityType".equals(typeName)) {
        throw new IllegalArgumentException("Non-entity ID (" + id + ")");
      }
      if (!"User".equals(typeName)) {
        throw new IllegalArgumentException("Can only handle user entities (" + id + ")");
      }
      GlobalID<User> userId = ctx.globalIDFor(Type.ofClass(User.class), id.getInternalID());
      return CompletableFuture.completedFuture(ctx.nodeRef(userId));
    }
  }
}
