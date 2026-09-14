package viaduct.tenant.runtime.execution.inputtype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @Test
  void inputCopyBuilderPreservesPresenceAndSnapshotsOverrides() {
    UserInput original =
        new UserInput(null, Map.of("name", "Alice", "balance", BigDecimal.TEN), null);
    UserInput roundTrip = original.toBuilder().build();
    UserInput.Builder builder = original.toBuilder().name("Bob").age(null);
    UserInput first = builder.build();
    UserInput nullRoundTrip = first.toBuilder().build();
    UserInput second = builder.age(30).build();
    UserInput chained = second.toBuilder().name("Charlie").build();

    assertEquals("Alice", original.getName());
    assertFalse(original.isPresent(UserInput.Fields.age));
    assertFalse(roundTrip.isPresent(UserInput.Fields.age));
    assertEquals("Bob", first.getName());
    assertTrue(first.isPresent(UserInput.Fields.age));
    assertNull(first.getAge());
    assertTrue(nullRoundTrip.isPresent(UserInput.Fields.age));
    assertNull(nullRoundTrip.getAge());
    assertEquals(30, second.getAge());
    assertEquals("Charlie", chained.getName());
    assertEquals(30, chained.getAge());
    assertEquals(BigDecimal.TEN, chained.getBalance());
  }

  // --- Resolvers ---

  @Resolver
  public static class UserByNameResolver extends QueryResolvers.UserByName {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      var args = ctx.getArguments();
      UserInput input = args.getInput().toBuilder().build();
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
