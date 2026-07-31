package viaduct.tenant.runtime.execution.inputtype;

import java.math.BigDecimal;
import java.math.BigInteger;
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
      BigDecimal balance = input.getBalance();
      BigInteger serial = input.getSerial();
      User user =
          User.builder(ctx)
              .name(input.getName())
              .age(input.getAge())
              .balance(balance)
              .serial(serial)
              .build();
      return CompletableFuture.completedFuture(user);
    }
  }
}
