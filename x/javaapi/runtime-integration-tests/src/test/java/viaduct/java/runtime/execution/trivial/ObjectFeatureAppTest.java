package viaduct.java.runtime.execution.trivial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.execution.trivial.resolverbases.FooResolvers;
import viaduct.java.runtime.execution.trivial.resolverbases.NestedFooResolvers;
import viaduct.java.runtime.execution.trivial.resolverbases.QueryResolvers;
import viaduct.java.runtime.fixtures.JavaFeatureAppTestBase;
import viaduct.service.api.ExecutionResult;

/**
 * Feature tests for basic object resolution patterns (Java equivalent).
 *
 * <p>This tests:
 *
 * <ul>
 *   <li>Shorthand and fragment @Resolver patterns
 *   <li>Object builders
 *   <li>Field resolvers returning lists of objects
 *   <li>Field resolvers with arguments
 * </ul>
 */
public class ObjectFeatureAppTest extends JavaFeatureAppTestBase {

  @Override
  public String getSdl() {
    return """
        #START_SCHEMA
        type Foo {
          shorthandBar: String @resolver
          fragmentBar: String @resolver
          baz: String @resolver
          nested: NestedFoo @resolver
          message: String @resolver
        }
        type NestedFoo {
          value: String @resolver
        }
        extend type Query {
          greeting: Foo @resolver
          fooList: [Foo] @resolver
          nestedFooList: [NestedFoo] @resolver
          fooWithArgs(message: String, count: Int): Foo @resolver
        }
        #END_SCHEMA
    """;
  }

  @Override
  protected String featureAppPackagePrefix() {
    return getClass().getPackage().getName();
  }

  @Override
  protected String grtPackagePrefix() {
    return getClass().getPackage().getName();
  }

  // --- Resolvers ---

  @Resolver
  public static class GreetingResolver extends QueryResolvers.Greeting {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder().build());
    }
  }

  @Resolver
  public static class BazResolver extends FooResolvers.Baz {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("world");
    }
  }

  @Resolver
  public static class NestedResolver extends FooResolvers.Nested {
    @Override
    public CompletableFuture<NestedFoo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(NestedFoo.builder().build());
    }
  }

  @Resolver
  public static class ValueResolver extends NestedFooResolvers.Value {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("nested_value");
    }
  }

  @Resolver(objectValueFragment = "baz")
  public static class ShorthandBarResolver extends FooResolvers.ShorthandBar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getBaz());
    }
  }

  @Resolver(
      objectValueFragment =
          """
          fragment _ on Foo {
            baz
            nested {
              value
            }
          }
          """)
  public static class FragmentBarResolver extends FooResolvers.FragmentBar {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      String baz = ctx.getObjectValue().getBaz();
      NestedFoo nested = ctx.getObjectValue().getNested();
      return CompletableFuture.completedFuture(baz + "-" + nested.getValue());
    }
  }

  @Resolver
  public static class FooListResolver extends QueryResolvers.FooList {
    @Override
    public CompletableFuture<List<Foo>> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          List.of(Foo.builder().build(), Foo.builder().build(), Foo.builder().build()));
    }
  }

  @Resolver
  public static class NestedFooListResolver extends QueryResolvers.NestedFooList {
    @Override
    public CompletableFuture<List<NestedFoo>> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          List.of(NestedFoo.builder().build(), NestedFoo.builder().build()));
    }
  }

  @Resolver
  public static class FooWithArgsResolver extends QueryResolvers.FooWithArgs {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      ctx.getArguments().getMessage();
      ctx.getArguments().getCount();
      return CompletableFuture.completedFuture(Foo.builder().build());
    }
  }

  @Resolver
  public static class MessageResolver extends FooResolvers.Message {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("message from resolver");
    }
  }

  // --- Tests ---

  @Test
  public void shorthandResolverPattern() {
    ExecutionResult result = execute("{ greeting { shorthandBar } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var greeting = (Map<String, Object>) data.get("greeting");
    assertThat(greeting.get("shorthandBar")).isEqualTo("world");
  }

  @Test
  public void fragmentResolverPattern() {
    ExecutionResult result = execute("{ greeting { fragmentBar } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var greeting = (Map<String, Object>) data.get("greeting");
    assertThat(greeting.get("fragmentBar")).isEqualTo("world-nested_value");
  }

  @Test
  public void fieldResolverReturnsListOfFooObjects() {
    ExecutionResult result = execute("{ fooList { baz nested { value } } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var fooList = (List<Map<String, Object>>) data.get("fooList");
    assertThat(fooList).hasSize(3);

    for (var foo : fooList) {
      assertThat(foo.get("baz")).isEqualTo("world");
      @SuppressWarnings("unchecked")
      var nested = (Map<String, Object>) foo.get("nested");
      assertThat(nested.get("value")).isEqualTo("nested_value");
    }
  }

  @Test
  public void fieldResolverReturnsListOfNestedFooObjects() {
    ExecutionResult result = execute("{ nestedFooList { value } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var nestedFooList = (List<Map<String, Object>>) data.get("nestedFooList");
    assertThat(nestedFooList).hasSize(2);

    for (var nestedFoo : nestedFooList) {
      assertThat(nestedFoo.get("value")).isEqualTo("nested_value");
    }
  }

  @Test
  public void fieldResolverWithArgumentsReturnsObjectType() {
    ExecutionResult result =
        execute("query { fooWithArgs(message: \"test message\", count: 5) { message baz } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var fooWithArgs = (Map<String, Object>) data.get("fooWithArgs");
    assertThat(fooWithArgs.get("message")).isEqualTo("message from resolver");
    assertThat(fooWithArgs.get("baz")).isEqualTo("world");
  }

  @Test
  public void fieldResolverWithNullArgumentsReturnsObjectType() {
    ExecutionResult result =
        execute("query { fooWithArgs(message: null, count: null) { message baz } }");

    assertThat(result.getErrors()).isEmpty();

    var data = result.getData();
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    var fooWithArgs = (Map<String, Object>) data.get("fooWithArgs");
    assertThat(fooWithArgs.get("message")).isEqualTo("message from resolver");
    assertThat(fooWithArgs.get("baz")).isEqualTo("world");
  }
}
