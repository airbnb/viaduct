package viaduct.tenant.runtime.execution.queryselections;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.Variable;
import viaduct.tenant.runtime.execution.queryselections.resolverbases.MutationResolvers;
import viaduct.tenant.runtime.execution.queryselections.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.queryselections.resolverbases.UserResolvers;

public class JavaQuerySelectionsContractTest extends QuerySelectionsContractTest {

  // --- Resolvers ---

  @Resolver
  public static class ViewerResolver extends QueryResolvers.Viewer {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.Viewer.Context ctx) {
      return CompletableFuture.completedFuture(
          User.builder(ctx).id("viewer-123").name("ViewerUser").build());
    }
  }

  @Resolver
  public static class NullableViewerResolver extends QueryResolvers.NullableViewer {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.NullableViewer.Context ctx) {
      return CompletableFuture.completedFuture(null);
    }
  }

  @Resolver
  public static class UserResolver extends QueryResolvers.User {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.User.Context ctx) {
      String userId = ctx.getArguments().getId();
      return CompletableFuture.completedFuture(
          User.builder(ctx).id(userId).name("User-" + userId).build());
    }
  }

  @Resolver(
      objectValueFragment = "fragment _ on User { id }",
      queryValueFragment = "fragment _ on Query { viewer { name } }")
  public static class DisplayNameResolver extends UserResolvers.DisplayName {
    @Override
    public CompletableFuture<String> resolve(UserResolvers.DisplayName.Context ctx) {
      String userId = ctx.getObjectValue().getId();
      User viewer = ctx.getQueryValue().getViewer();
      String viewerName = viewer != null ? viewer.getName() : null;
      return CompletableFuture.completedFuture(userId + "-displayedBy-" + viewerName);
    }
  }

  @Resolver(
      objectValueFragment = "fragment _ on User { id }",
      queryValueFragment = "fragment _ on Query { nullableViewer { name } }")
  public static class DisplayNameFromNullViewerResolver
      extends UserResolvers.DisplayNameFromNullViewer {
    @Override
    public CompletableFuture<String> resolve(UserResolvers.DisplayNameFromNullViewer.Context ctx) {
      String userId = ctx.getObjectValue().getId();
      User viewer = ctx.getQueryValue().getNullableViewer();
      String viewerName = viewer != null ? viewer.getName() : "Unknown";
      return CompletableFuture.completedFuture(userId + "-displayedBy-" + viewerName);
    }
  }

  @Resolver(
      objectValueFragment = "fragment _ on User { name }",
      queryValueFragment = "fragment _ on Query { viewer { id displayName } }")
  public static class GreetingResolver extends UserResolvers.Greeting {
    @Override
    public CompletableFuture<String> resolve(UserResolvers.Greeting.Context ctx) {
      String userName = ctx.getObjectValue().getName();
      User viewer = ctx.getQueryValue().getViewer();
      String viewerId = viewer != null ? viewer.getId() : null;
      String displayName =
          viewer != null && viewer.getDisplayName() != null
              ? viewer.getDisplayName()
              : "UnknownViewer";
      return CompletableFuture.completedFuture(
          "Hello " + userName + ", from " + viewerId + " (displayed by " + displayName + ")");
    }
  }

  @Resolver(
      queryValueFragment =
          "fragment _ on Query { viewer { id name } user(id: $userId) { id name } }",
      variables = {@Variable(name = "userId", fromArgument = "userId")})
  public static class UpdateUserWithViewerInfoResolver
      extends MutationResolvers.UpdateUserWithViewerInfo {
    @Override
    public CompletableFuture<UpdateResult> resolve(
        MutationResolvers.UpdateUserWithViewerInfo.Context ctx) {
      String userId = ctx.getArguments().getUserId();
      User viewer = ctx.getQueryValue().getViewer();
      User user = ctx.getQueryValue().getUser();

      boolean success = viewer != null && user != null;
      String message;
      if (viewer == null) {
        message = "No viewer found";
      } else if (user == null) {
        message = "User " + userId + " not found";
      } else {
        message =
            "Updated user "
                + user.getName()
                + " ("
                + user.getId()
                + ") with info from viewer "
                + viewer.getName()
                + " ("
                + viewer.getId()
                + ")";
      }

      return CompletableFuture.completedFuture(
          UpdateResult.builder(ctx).success(success).message(message).build());
    }
  }
}
