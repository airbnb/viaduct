package viaduct.tenant.runtime.execution.inputtype;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.inputtype.resolverbases.QueryResolvers;

public class JavaInputTypeContractTest extends InputTypeContractTest {

  @Test
  void generatedInputsAndArgumentsDistinguishOmittedFieldsFromExplicitNull() {
    UserInput omittedInput = new UserInput(null, Map.of("name", "Alice"), null);
    Map<String, Object> explicitNullInputData = new HashMap<>();
    explicitNullInputData.put("name", "Alice");
    explicitNullInputData.put("age", null);
    UserInput explicitNullInput = new UserInput(null, explicitNullInputData, null);

    assertFalse(omittedInput.isPresent(UserInput.Fields.age));
    assertTrue(explicitNullInput.isPresent(UserInput.Fields.age));

    Query_UserByName_Arguments omittedArguments =
        new Query_UserByName_Arguments(null, Map.of("input", omittedInput), null);
    Map<String, Object> explicitNullArgumentsData = new HashMap<>();
    explicitNullArgumentsData.put("input", omittedInput);
    explicitNullArgumentsData.put("limit", null);
    Query_UserByName_Arguments explicitNullArguments =
        new Query_UserByName_Arguments(null, explicitNullArgumentsData, null);

    assertFalse(omittedArguments.isPresent(Query_UserByName_Arguments.Fields.limit));
    assertTrue(explicitNullArguments.isPresent(Query_UserByName_Arguments.Fields.limit));
  }

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
