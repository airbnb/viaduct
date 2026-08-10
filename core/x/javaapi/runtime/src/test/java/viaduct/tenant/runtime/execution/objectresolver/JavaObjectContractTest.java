package viaduct.tenant.runtime.execution.objectresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import graphql.schema.GraphQLInputObjectType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.ViaductSchema;
import viaduct.engine.api.spi.FieldResolverExecutor;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ResolverClassFinder;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.service.api.spi.GlobalIDCodec;
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.FooResolvers;
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.NestedFooResolvers;
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.PersonResolvers;
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.QueryResolvers;

public class JavaObjectContractTest extends ObjectContractTest {

  // --- Resolvers ---

  @Resolver
  public static class GreetingResolver extends QueryResolvers.Greeting {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Foo.builder(ctx).build());
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
      return CompletableFuture.completedFuture(NestedFoo.builder(ctx).build());
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
          List.of(Foo.builder(ctx).build(), Foo.builder(ctx).build(), Foo.builder(ctx).build()));
    }
  }

  @Resolver
  public static class NestedFooListResolver extends QueryResolvers.NestedFooList {
    @Override
    public CompletableFuture<List<NestedFoo>> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          List.of(NestedFoo.builder(ctx).build(), NestedFoo.builder(ctx).build()));
    }
  }

  @Resolver
  public static class FooWithArgsResolver extends QueryResolvers.FooWithArgs {
    @Override
    public CompletableFuture<Foo> resolve(Context ctx) {
      ctx.getArguments().getMessage();
      ctx.getArguments().getCount();
      return CompletableFuture.completedFuture(Foo.builder(ctx).build());
    }
  }

  @Resolver
  public static class MessageResolver extends FooResolvers.Message {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("message from resolver");
    }
  }

  @Resolver
  public static class PersonByNameResolver extends QueryResolvers.PersonByName {
    @Override
    public CompletableFuture<Person> resolve(Context ctx) {
      String name = ctx.getArguments().getName();
      Address address =
          Address.builder(ctx).street("123 Main St").city("San Francisco").country("USA").build();
      Person person = Person.builder(ctx).name(name).age(30).address(address).build();
      return CompletableFuture.completedFuture(person);
    }
  }

  @Resolver(objectValueFragment = "address { street city country }")
  public static class FullAddressResolver extends PersonResolvers.FullAddress {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      Person person = ctx.getObjectValue();
      Address address = person.getAddress();
      if (address == null) {
        return CompletableFuture.completedFuture("No address");
      }
      String fullAddress =
          address.getStreet() + ", " + address.getCity() + ", " + address.getCountry();
      return CompletableFuture.completedFuture(fullAddress);
    }
  }

  @Resolver
  public static class PersonGreetingResolver extends PersonResolvers.Greeting {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("Hello!");
    }
  }

  // --- Builder reuse tests ---

  private interface StubContext extends ExecutionContext, InternalContext {}

  private static final ExecutionContext STUB_CTX =
      new StubContext() {
        @Override
        public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String id) {
          throw new UnsupportedOperationException();
        }

        @Override
        public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Object getRequestContext() {
          return null;
        }

        @Override
        public ViaductSchema getSchema() {
          throw new UnsupportedOperationException();
        }

        @Override
        public GraphQLInputObjectType getArgumentsInputType(
            String name, String containingTypeName, String fieldName) {
          throw new UnsupportedOperationException();
        }

        @Override
        public GlobalIDCodec getGlobalIDCodec() {
          throw new UnsupportedOperationException();
        }

        @Override
        public ResolverClassFinder getClassFinder() {
          throw new UnsupportedOperationException();
        }

        @Override
        public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  public void builderReuseDoesNotAliasObjects() {
    Address.Builder builder = Address.builder(STUB_CTX).street("123 Main").city("SF").country("US");
    Address first = builder.build();
    builder.street("456 Oak").city("NYC");
    Address second = builder.build();

    assertEquals("123 Main", first.getStreet());
    assertEquals("SF", first.getCity());
    assertEquals("US", first.getCountry());
    assertEquals("456 Oak", second.getStreet());
    assertEquals("NYC", second.getCity());
    assertEquals("US", second.getCountry());
  }

  // --- Java-only wiring tests ---

  @Test
  public void fullAddressResolverHasObjectSelectionSetWired() {
    FieldResolverExecutor executor = fieldResolverExecutorFor("Person", "fullAddress");

    assertNotNull(executor, "Executor for Person.fullAddress should exist");
    assertNotNull(
        executor.getObjectSelectionSet(),
        "FullAddressResolver should have objectSelectionSet wired from objectValueFragment");
    assertNull(
        executor.getQuerySelectionSet(),
        "FullAddressResolver should have null querySelectionSet (no queryValueFragment)");
  }

  @Test
  public void greetingResolverWithoutObjectValueFragmentHasNullSelectionSet() {
    FieldResolverExecutor executor = fieldResolverExecutorFor("Person", "greeting");

    assertNotNull(executor, "Executor for Person.greeting should exist");
    assertNull(
        executor.getObjectSelectionSet(),
        "GreetingResolver without objectValueFragment should have null objectSelectionSet");
    assertNull(
        executor.getQuerySelectionSet(),
        "GreetingResolver without queryValueFragment should have null querySelectionSet");
  }
}
