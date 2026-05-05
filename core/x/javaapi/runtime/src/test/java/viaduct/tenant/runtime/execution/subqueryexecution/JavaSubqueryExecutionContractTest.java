package viaduct.tenant.runtime.execution.subqueryexecution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.CalculatorResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.ContainerResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level1Resolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level2Resolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.MutationResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.UserResolvers;

public class JavaSubqueryExecutionContractTest extends SubqueryExecutionContractTest {

  private static int counter = 0;

  private final DefaultJavaResolverClassFinder classFinder =
      new DefaultJavaResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final JavaModuleBootstrapper bootstrapper =
      new JavaModuleBootstrapper(classFinder, CodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>
      createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
  }

  @BeforeEach
  public void resetCounterBeforeTest() {
    counter = 0;
  }

  @Override
  protected void resetCounter() {
    counter = 0;
  }

  // --- Query resolvers ---

  @Resolver
  public static class RootValueResolver extends QueryResolvers.RootValue {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(42);
    }
  }

  @Resolver
  public static class FirstNameResolver extends QueryResolvers.FirstName {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("Alice");
    }
  }

  @Resolver
  public static class LastNameResolver extends QueryResolvers.LastName {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("Smith");
    }
  }

  @Resolver
  public static class MultiplyResolver extends QueryResolvers.Multiply {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getN() * 2);
    }
  }

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    public CompletableFuture<Container> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Container.builder().build());
    }
  }

  @Resolver
  public static class UserResolver extends QueryResolvers.User {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      return CompletableFuture.completedFuture(User.builder().build());
    }
  }

  @Resolver
  public static class CalculatorResolver extends QueryResolvers.Calculator {
    @Override
    public CompletableFuture<Calculator> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Calculator.builder().build());
    }
  }

  @Resolver
  public static class Level1Resolver extends QueryResolvers.Level1 {
    @Override
    public CompletableFuture<Level1> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Level1.builder().build());
    }
  }

  @Resolver
  public static class BaseValueResolver extends QueryResolvers.BaseValue {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(10);
    }
  }

  @Resolver
  public static class CounterValueResolver extends QueryResolvers.CounterValue {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(counter);
    }
  }

  // --- Mutation resolvers ---

  @Resolver
  public static class IncrementCounterResolver extends MutationResolvers.IncrementCounter {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return CompletableFuture.completedFuture(++counter);
    }
  }

  @Resolver
  public static class TriggerNestedMutationResolver
      extends MutationResolvers.TriggerNestedMutation {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return ctx.mutation("incrementCounter").thenApply(m -> m.getIncrementCounter());
    }
  }

  @Resolver
  public static class FetchFromQueryDuringMutationResolver
      extends MutationResolvers.FetchFromQueryDuringMutation {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.query("firstName lastName")
          .thenApply(
              q -> {
                String first = q.getFirstName() != null ? q.getFirstName() : "";
                String last = q.getLastName() != null ? q.getLastName() : "";
                return "Mutation processed for: " + first + " " + last;
              });
    }
  }

  @Resolver
  public static class MutationWithVariablesResolver
      extends MutationResolvers.MutationWithVariables {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int multiplier = ctx.getArguments().getMultiplier();
      return ctx.mutation("incrementCounter")
          .thenApply(
              m -> {
                Integer counterValue = m.getIncrementCounter();
                return counterValue != null ? counterValue * multiplier : 0;
              });
    }
  }

  @Resolver
  public static class QueryWithVariablesFromMutationResolver
      extends MutationResolvers.QueryWithVariablesFromMutation {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int n = ctx.getArguments().getN();
      return ctx.query("multiply(n: $n)", Map.of("n", n))
          .thenApply(q -> q.getMultiply() != null ? q.getMultiply() : 0);
    }
  }

  @Resolver
  public static class ProfileResolver extends QueryResolvers.Profile {
    @Override
    public CompletableFuture<Profile> resolve(Context ctx) {
      return CompletableFuture.completedFuture(
          Profile.builder().firstName("Jane").lastName("Doe").build());
    }
  }

  // --- Container resolvers ---

  @Resolver
  public static class DerivedFromNestedQueryResolver
      extends ContainerResolvers.DerivedFromNestedQuery {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.query("profile { firstName }")
          .thenApply(
              q -> {
                Profile profile = q.getProfile();
                return profile != null ? profile.getFirstName() : "";
              });
    }
  }

  @Resolver
  public static class DerivedFromQueryResolver extends ContainerResolvers.DerivedFromQuery {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return ctx.query("rootValue")
          .thenApply(
              q -> {
                Integer rootValue = q.getRootValue();
                return rootValue != null ? rootValue * 2 : 0;
              });
    }
  }

  @Resolver(queryValueFragment = "fragment _ on Query { rootValue }")
  public static class ViaQuerySelectionsResolver extends ContainerResolvers.ViaQuerySelections {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      Query queryValue = ctx.getQueryValue();
      Integer rootValue = queryValue.getRootValue();
      return CompletableFuture.completedFuture(rootValue != null ? rootValue : 0);
    }
  }

  @Resolver
  public static class ViaCtxQueryResolver extends ContainerResolvers.ViaCtxQuery {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return ctx.query("rootValue").thenApply(q -> q.getRootValue() != null ? q.getRootValue() : 0);
    }
  }

  @Resolver
  public static class QueryWithVariablesResolver extends ContainerResolvers.QueryWithVariables {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int multiplier = ctx.getArguments().getMultiplier();
      return ctx.query("multiply(n: $n)", Map.of("n", multiplier))
          .thenApply(q -> q.getMultiply() != null ? q.getMultiply() : 0);
    }
  }

  // --- User resolver ---

  @Resolver
  public static class FullNameResolver extends UserResolvers.FullName {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.query("firstName lastName")
          .thenApply(
              q -> {
                String first = q.getFirstName() != null ? q.getFirstName() : "";
                String last = q.getLastName() != null ? q.getLastName() : "";
                return first + " " + last;
              });
    }
  }

  // --- Calculator resolver ---

  @Resolver
  public static class DoubleResolver extends CalculatorResolvers.Double {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int input = ctx.getArguments().getInput();
      return ctx.query("multiply(n: " + input + ")")
          .thenApply(q -> q.getMultiply() != null ? q.getMultiply() : 0);
    }
  }

  // --- Level1 resolver ---

  @Resolver
  public static class Level2Resolver extends Level1Resolvers.Level2 {
    @Override
    public CompletableFuture<Level2> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Level2.builder().build());
    }
  }

  // --- Level2 resolver ---

  @Resolver
  public static class DerivedValueResolver extends Level2Resolvers.DerivedValue {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      return ctx.query("baseValue")
          .thenApply(
              q -> {
                Integer baseValue = q.getBaseValue();
                return baseValue != null ? baseValue * 3 : 0;
              });
    }
  }
}
