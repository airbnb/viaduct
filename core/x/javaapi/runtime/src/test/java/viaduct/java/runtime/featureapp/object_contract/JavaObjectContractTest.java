package viaduct.java.runtime.featureapp.object_contract;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.FieldResolverExecutor;
import viaduct.engine.api.spi.TenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.FooResolvers;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.NestedFooResolvers;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.PersonResolvers;
import viaduct.java.runtime.featureapp.object_contract.resolverbases.QueryResolvers;
import viaduct.service.api.SchemaId;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.TenantCodeInjector;
import viaduct.tenant.runtime.fixtures.objectcontract.ObjectContractTest;

public class JavaObjectContractTest extends ObjectContractTest {

  private final DefaultJavaResolverClassFinder classFinder =
      new DefaultJavaResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final JavaModuleBootstrapper bootstrapper =
      new JavaModuleBootstrapper(classFinder, TenantCodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
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

  @Resolver
  public static class PersonByNameResolver extends QueryResolvers.PersonByName {
    @Override
    public CompletableFuture<Person> resolve(Context ctx) {
      String name = ctx.getArguments().getName();
      Address address =
          Address.builder().street("123 Main St").city("San Francisco").country("USA").build();
      Person person = Person.builder().name(name).age(30).address(address).build();
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

  // --- Java-only wiring tests ---

  @Test
  public void fullAddressResolverHasObjectSelectionSetWired() {
    FieldResolverExecutor executor = getFieldResolverExecutor("Person", "fullAddress");

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
    FieldResolverExecutor executor = getFieldResolverExecutor("Person", "greeting");

    assertNotNull(executor, "Executor for Person.greeting should exist");
    assertNull(
        executor.getObjectSelectionSet(),
        "GreetingResolver without objectValueFragment should have null objectSelectionSet");
    assertNull(
        executor.getQuerySelectionSet(),
        "GreetingResolver without queryValueFragment should have null querySelectionSet");
  }

  private FieldResolverExecutor getFieldResolverExecutor(String typeName, String fieldName) {
    tryBuildViaductService();
    var schema = viaductService.getEngineRegistry().getSchema(SchemaId.Full.INSTANCE);
    var executors = bootstrapper.fieldResolverExecutors(schema);
    for (var entry : executors) {
      var coordinate = entry.getFirst();
      if (typeName.equals(coordinate.getFirst()) && fieldName.equals(coordinate.getSecond())) {
        return entry.getSecond();
      }
    }
    return null;
  }
}
