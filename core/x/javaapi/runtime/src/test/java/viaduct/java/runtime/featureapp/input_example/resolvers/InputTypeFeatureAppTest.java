package viaduct.java.runtime.featureapp.input_example.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.featureapp.input_example.grt.Query_userByName_Arguments;
import viaduct.java.runtime.featureapp.input_example.grt.User;
import viaduct.java.runtime.featureapp.input_example.grt.UserInput;
import viaduct.java.runtime.featureapp.input_example.resolverbases.QueryResolvers;
import viaduct.java.runtime.fixtures.JavaFeatureAppTestBase;
import viaduct.service.api.ExecutionResult;

/**
 * Example demonstrating Java FeatureApp pattern with input types.
 *
 * <p>This test shows how to:
 *
 * <ul>
 *   <li>Define a GraphQL input type in the schema
 *   <li>Create arguments that contain nested input objects
 *   <li>Access typed arguments in the resolver via ctx.getArguments()
 *   <li>Test with variables containing input objects
 * </ul>
 *
 * <p><b>How it works:</b>
 *
 * <ol>
 *   <li>UserInput implements GraphQLInput for nested input objects
 *   <li>Query_userByName_Arguments implements Arguments for field arguments
 *   <li>QueryResolvers.UserByName uses typed Arguments instead of Arguments.None
 *   <li>Resolver accesses arguments via ctx.getArguments().getInput()
 * </ol>
 */
public class InputTypeFeatureAppTest extends JavaFeatureAppTestBase {

  /**
   * Returns the SDL schema for Viaduct engine. Do NOT define 'type Query' - Viaduct provides root
   * types automatically. Use only 'extend type Query' for your resolvers.
   */
  @Override
  public String getSdl() {
    return """
    input UserInput {
        name: String!
        age: Int
    }

    type User {
        name: String!
        age: Int
    }

    extend type Query {
        userByName(input: UserInput!, limit: Int): User @resolver
    }
    """;
  }

  /**
   * Resolver implementation that uses input type arguments.
   *
   * <p>This resolver demonstrates accessing typed arguments via ctx.getArguments() and extracting
   * nested input objects.
   */
  @Resolver
  public static class UserByNameResolver extends QueryResolvers.UserByName {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      Query_userByName_Arguments args = ctx.getArguments();
      UserInput input = args.getInput();

      // Echo back the input as a User
      User user = User.builder().name(input.getName()).age(input.getAge()).build();

      return CompletableFuture.completedFuture(user);
    }
  }

  @Test
  public void resolverReceivesInputType() {
    ExecutionResult result =
        execute(
            "query($input: UserInput!) { userByName(input: $input) { name age } }",
            Map.of("input", Map.of("name", "Alice", "age", 30)));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getData()).isNotNull();

    var data = result.getData();
    @SuppressWarnings("unchecked")
    var user = (Map<String, Object>) data.get("userByName");

    Assertions.assertNotNull(user);
    assertThat(user.get("name")).isEqualTo("Alice");
    assertThat(user.get("age")).isEqualTo(30);
  }

  @Test
  public void inputTypeWithNullableField() {
    // age is nullable, so we can omit it
    ExecutionResult result =
        execute(
            "query($input: UserInput!) { userByName(input: $input) { name age } }",
            Map.of("input", Map.of("name", "Bob")));

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    Assertions.assertNotNull(data);
    @SuppressWarnings("unchecked")
    var user = (Map<String, Object>) data.get("userByName");

    Assertions.assertNotNull(user);
    assertThat(user.get("name")).isEqualTo("Bob");
    assertThat(user.get("age")).isNull();
  }

  @Test
  public void inlineInputType() {
    // Test with inline input object (not using variables)
    ExecutionResult result =
        execute("{ userByName(input: { name: \"Charlie\", age: 25 }) { name age } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    Assertions.assertNotNull(data);
    @SuppressWarnings("unchecked")
    var user = (Map<String, Object>) data.get("userByName");

    Assertions.assertNotNull(user);
    assertThat(user.get("name")).isEqualTo("Charlie");
    assertThat(user.get("age")).isEqualTo(25);
  }
}
