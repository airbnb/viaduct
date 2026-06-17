package viaduct.tenant.runtime.execution.inputtype;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.inputtype.resolverbases.QueryResolvers;

public class JavaInputTypeContractTest extends InputTypeContractTest {

  // --- Resolvers ---

  @Resolver
  public static class UserByNameResolver extends QueryResolvers.UserByName {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      var args = ctx.getArguments();
      UserInput input = args.getInput();
      User user = User.builder(ctx).name(input.getName()).age(input.getAge()).build();
      return CompletableFuture.completedFuture(user);
    }
  }
}
