package viaduct.java.runtime.featureapp.object_example.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.spi.FieldResolverExecutor;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.featureapp.object_example.grt.Address;
import viaduct.java.runtime.featureapp.object_example.grt.Person;
import viaduct.java.runtime.featureapp.object_example.resolverbases.PersonResolvers;
import viaduct.java.runtime.featureapp.object_example.resolverbases.QueryResolvers;
import viaduct.java.runtime.fixtures.JavaFeatureAppTestBase;
import viaduct.service.api.ExecutionResult;

/**
 * Example demonstrating Java FeatureApp pattern with object types.
 *
 * <p>This test shows how to:
 *
 * <ul>
 *   <li>Define GraphQL object types (Person, Address) in the schema
 *   <li>Create resolvers that return object types with nested objects
 *   <li>Create object field resolvers (Person.fullAddress) that compute derived values
 *   <li>Test nested object field access and nullable fields
 * </ul>
 *
 * <p><b>How it works:</b>
 *
 * <ol>
 *   <li>Person and Address implement CompositeOutput (simulating codegen output)
 *   <li>QueryResolvers.PersonResolver handles Query.person field
 *   <li>PersonResolvers.FullAddressResolver handles Person.fullAddress field (object field
 *       resolver)
 *   <li>The object field resolver uses ctx.getObjectValue() to access the Person instance
 *   <li>JavaFeatureAppTestBase bootstraps both resolvers via JavaModuleBootstrapper
 * </ol>
 */
public class ObjectTypeFeatureAppTest extends JavaFeatureAppTestBase {

  /**
   * Returns the SDL schema for Viaduct engine. Do NOT define 'type Query' - Viaduct provides root
   * types automatically. Use only 'extend type Query' for your resolvers.
   */
  @Override
  public String getSdl() {
    return """
    type Address {
        street: String!
        city: String!
        country: String
    }

    type Person {
        name: String!
        age: Int
        address: Address
        fullAddress: String @resolver
        greeting: String @resolver
    }

    extend type Query {
        person(name: String!): Person @resolver
    }
    """;
  }

  /**
   * Resolver implementation for Query.person field.
   *
   * <p>This resolver creates a Person object with nested Address based on the name argument.
   */
  @Resolver
  public static class PersonResolver extends QueryResolvers.PersonResolver {
    @Override
    public CompletableFuture<Person> resolve(Context ctx) {
      String name = ctx.getArguments().getName();

      Address address =
          Address.builder().street("123 Main St").city("San Francisco").country("USA").build();

      Person person = Person.builder().name(name).age(30).address(address).build();

      return CompletableFuture.completedFuture(person);
    }
  }

  /**
   * Resolver implementation for Person.fullAddress field.
   *
   * <p>This resolver demonstrates an object field resolver with required selections - it operates
   * on Person (not Query) and uses objectValueFragment to declare which fields it needs from the
   * parent object. The Viaduct engine ensures these fields are resolved before this resolver runs.
   *
   * <p>The objectValueFragment declares that we need the nested address fields (street, city,
   * country) from the parent Person object. This enables ctx.getObjectValue().getAddress() to
   * return a fully populated Address object.
   */
  @Resolver(objectValueFragment = "address { street city country }")
  public static class FullAddressResolver extends PersonResolvers.FullAddressResolver {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      // Access the parent Person object with its required selections populated
      Person person = ctx.getObjectValue();
      Address address = person.getAddress();

      if (address == null) {
        return CompletableFuture.completedFuture("No address");
      }

      // Build the full address string from the required selections
      String fullAddress =
          address.getStreet() + ", " + address.getCity() + ", " + address.getCountry();
      return CompletableFuture.completedFuture(fullAddress);
    }
  }

  /**
   * Resolver implementation for Person.greeting field WITHOUT objectValueFragment.
   *
   * <p>This resolver demonstrates a resolver that does NOT use required selections. It returns a
   * simple greeting without accessing any parent object fields. This serves as a contrast to
   * FullAddressResolver to verify that resolvers without objectValueFragment have null
   * objectSelectionSet.
   */
  @Resolver
  public static class GreetingResolver extends PersonResolvers.GreetingResolver {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      // This resolver doesn't need any fields from the parent object
      return CompletableFuture.completedFuture("Hello!");
    }
  }

  @Test
  public void personResolverReturnsObjectType() {
    ExecutionResult result =
        execute(
            "query($name: String!) { person(name: $name) { name age } }", Map.of("name", "Alice"));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getData()).isNotNull();

    var data = result.getData();
    @SuppressWarnings("unchecked")
    var person = (Map<String, Object>) data.get("person");

    Assertions.assertNotNull(person);
    assertThat(person.get("name")).isEqualTo("Alice");
    assertThat(person.get("age")).isEqualTo(30);
  }

  @Test
  public void nestedObjectTypesAreAccessible() {
    ExecutionResult result =
        execute(
            "query($name: String!) { person(name: $name) { name address { street city country } }"
                + " }",
            Map.of("name", "Bob"));

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    Assertions.assertNotNull(data);
    @SuppressWarnings("unchecked")
    var person = (Map<String, Object>) data.get("person");

    Assertions.assertNotNull(person);
    assertThat(person.get("name")).isEqualTo("Bob");

    @SuppressWarnings("unchecked")
    var address = (Map<String, Object>) person.get("address");
    Assertions.assertNotNull(address);
    assertThat(address.get("street")).isEqualTo("123 Main St");
    assertThat(address.get("city")).isEqualTo("San Francisco");
    assertThat(address.get("country")).isEqualTo("USA");
  }

  @Test
  public void objectFieldResolverComputesValue() {
    ExecutionResult result =
        execute(
            "query($name: String!) { person(name: $name) { name fullAddress } }",
            Map.of("name", "Charlie"));

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    Assertions.assertNotNull(data);
    @SuppressWarnings("unchecked")
    var person = (Map<String, Object>) data.get("person");

    Assertions.assertNotNull(person);
    assertThat(person.get("name")).isEqualTo("Charlie");
    // The resolver returns a computed full address
    assertThat(person.get("fullAddress")).isEqualTo("123 Main St, San Francisco, USA");
  }

  @Test
  public void nullableFieldsHandledCorrectly() {
    // Test with inline query - the resolver still returns a Person with all fields populated
    // This test verifies that nullable fields (age, country) are correctly returned
    ExecutionResult result = execute("{ person(name: \"Dave\") { name age address { country } } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    Assertions.assertNotNull(data);
    @SuppressWarnings("unchecked")
    var person = (Map<String, Object>) data.get("person");

    Assertions.assertNotNull(person);
    assertThat(person.get("name")).isEqualTo("Dave");
    // age is nullable but our resolver sets it to 30
    assertThat(person.get("age")).isEqualTo(30);

    @SuppressWarnings("unchecked")
    var address = (Map<String, Object>) person.get("address");
    Assertions.assertNotNull(address);
    // country is nullable and our resolver sets it to "USA"
    assertThat(address.get("country")).isEqualTo("USA");
  }

  // ==========================================================================
  // Required Selections Wiring Tests
  // ==========================================================================

  @Test
  public void fullAddressResolverHasObjectSelectionSetWired() {
    // Verify that the FullAddressResolver with objectValueFragment has objectSelectionSet wired
    FieldResolverExecutor executor = getFieldResolverExecutor("Person", "fullAddress");

    Assertions.assertNotNull(executor, "Executor for Person.fullAddress should exist");
    Assertions.assertNotNull(
        executor.getObjectSelectionSet(),
        "FullAddressResolver should have objectSelectionSet wired from objectValueFragment");
    Assertions.assertNull(
        executor.getQuerySelectionSet(),
        "FullAddressResolver should have null querySelectionSet (no queryValueFragment)");
  }

  @Test
  public void greetingResolverWithoutObjectValueFragmentHasNullSelectionSet() {
    // Verify that the GreetingResolver WITHOUT objectValueFragment has null objectSelectionSet
    FieldResolverExecutor executor = getFieldResolverExecutor("Person", "greeting");

    Assertions.assertNotNull(executor, "Executor for Person.greeting should exist");
    Assertions.assertNull(
        executor.getObjectSelectionSet(),
        "GreetingResolver without objectValueFragment should have null objectSelectionSet");
    Assertions.assertNull(
        executor.getQuerySelectionSet(),
        "GreetingResolver without queryValueFragment should have null querySelectionSet");
  }
}
