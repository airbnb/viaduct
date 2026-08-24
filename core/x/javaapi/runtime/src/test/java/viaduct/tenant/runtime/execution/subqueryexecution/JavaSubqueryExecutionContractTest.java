package viaduct.tenant.runtime.execution.subqueryexecution;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.CalculatorResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.ContainerResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level1Resolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level2Resolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.MutationResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.UserResolvers;

public class JavaSubqueryExecutionContractTest extends SubqueryExecutionContractTest {

  private static int counter = 0;

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
    public CompletableFuture<Integer> resolve(QueryResolvers.RootValue.Context ctx) {
      return CompletableFuture.completedFuture(42);
    }
  }

  @Resolver
  public static class FirstNameResolver extends QueryResolvers.FirstName {
    @Override
    public CompletableFuture<String> resolve(QueryResolvers.FirstName.Context ctx) {
      return CompletableFuture.completedFuture("Alice");
    }
  }

  @Resolver
  public static class LastNameResolver extends QueryResolvers.LastName {
    @Override
    public CompletableFuture<String> resolve(QueryResolvers.LastName.Context ctx) {
      return CompletableFuture.completedFuture("Smith");
    }
  }

  @Resolver
  public static class MultiplyResolver extends QueryResolvers.Multiply {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.Multiply.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getN() * 2);
    }
  }

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    public CompletableFuture<Container> resolve(QueryResolvers.Container.Context ctx) {
      return CompletableFuture.completedFuture(Container.builder(ctx).build());
    }
  }

  @Resolver
  public static class UserResolver extends QueryResolvers.User {
    @Override
    public CompletableFuture<User> resolve(QueryResolvers.User.Context ctx) {
      return CompletableFuture.completedFuture(User.builder(ctx).build());
    }
  }

  @Resolver
  public static class CalculatorResolver extends QueryResolvers.Calculator {
    @Override
    public CompletableFuture<Calculator> resolve(QueryResolvers.Calculator.Context ctx) {
      return CompletableFuture.completedFuture(Calculator.builder(ctx).build());
    }
  }

  @Resolver
  public static class Level1Resolver extends QueryResolvers.Level1 {
    @Override
    public CompletableFuture<Level1> resolve(QueryResolvers.Level1.Context ctx) {
      return CompletableFuture.completedFuture(Level1.builder(ctx).build());
    }
  }

  @Resolver
  public static class BaseValueResolver extends QueryResolvers.BaseValue {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.BaseValue.Context ctx) {
      return CompletableFuture.completedFuture(10);
    }
  }

  @Resolver
  public static class CounterValueResolver extends QueryResolvers.CounterValue {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.CounterValue.Context ctx) {
      return CompletableFuture.completedFuture(counter);
    }
  }

  // --- Mutation resolvers ---

  @Resolver
  public static class IncrementCounterResolver extends MutationResolvers.IncrementCounter {
    @Override
    public CompletableFuture<Integer> resolve(MutationResolvers.IncrementCounter.Context ctx) {
      return CompletableFuture.completedFuture(++counter);
    }
  }

  @Resolver
  public static class TriggerNestedMutationResolver
      extends MutationResolvers.TriggerNestedMutation {
    @Override
    public CompletableFuture<Integer> resolve(MutationResolvers.TriggerNestedMutation.Context ctx) {
      return ctx.mutation("incrementCounter").thenApply(m -> m.getIncrementCounterOrThrow());
    }
  }

  @Resolver
  public static class FetchFromQueryDuringMutationResolver
      extends MutationResolvers.FetchFromQueryDuringMutation {
    @Override
    public CompletableFuture<String> resolve(
        MutationResolvers.FetchFromQueryDuringMutation.Context ctx) {
      return ctx.query("firstName lastName")
          .thenApply(
              q -> {
                String first = q.getFirstNameOrThrow() != null ? q.getFirstNameOrThrow() : "";
                String last = q.getLastNameOrThrow() != null ? q.getLastNameOrThrow() : "";
                return "Mutation processed for: " + first + " " + last;
              });
    }
  }

  @Resolver
  public static class MutationWithVariablesResolver
      extends MutationResolvers.MutationWithVariables {
    @Override
    public CompletableFuture<Integer> resolve(MutationResolvers.MutationWithVariables.Context ctx) {
      int multiplier = ctx.getArguments().getMultiplier();
      return ctx.mutation("incrementCounter")
          .thenApply(
              m -> {
                Integer counterValue = m.getIncrementCounterOrThrow();
                return counterValue != null ? counterValue * multiplier : 0;
              });
    }
  }

  @Resolver
  public static class QueryWithVariablesFromMutationResolver
      extends MutationResolvers.QueryWithVariablesFromMutation {
    @Override
    public CompletableFuture<Integer> resolve(
        MutationResolvers.QueryWithVariablesFromMutation.Context ctx) {
      int n = ctx.getArguments().getN();
      return ctx.query("multiply(n: $n)", Map.of("n", n))
          .thenApply(q -> q.getMultiplyOrThrow() != null ? q.getMultiplyOrThrow() : 0);
    }
  }

  @Resolver
  public static class ProfileResolver extends QueryResolvers.Profile {
    @Override
    public CompletableFuture<Profile> resolve(QueryResolvers.Profile.Context ctx) {
      return CompletableFuture.completedFuture(
          Profile.builder(ctx).firstName("Jane").lastName("Doe").build());
    }
  }

  // --- Container resolvers ---

  @Resolver
  public static class DerivedFromNestedQueryResolver
      extends ContainerResolvers.DerivedFromNestedQuery {
    @Override
    public CompletableFuture<String> resolve(
        ContainerResolvers.DerivedFromNestedQuery.Context ctx) {
      return ctx.query("profile { firstName }")
          .thenApply(
              q -> {
                Profile profile = q.getProfileOrThrow();
                return profile != null ? profile.getFirstNameOrThrow() : "";
              });
    }
  }

  @Resolver
  public static class DerivedFromQueryResolver extends ContainerResolvers.DerivedFromQuery {
    @Override
    public CompletableFuture<Integer> resolve(ContainerResolvers.DerivedFromQuery.Context ctx) {
      return ctx.query("rootValue")
          .thenApply(
              q -> {
                Integer rootValue = q.getRootValueOrThrow();
                return rootValue != null ? rootValue * 2 : 0;
              });
    }
  }

  @Resolver(queryValueFragment = "fragment _ on Query { rootValue }")
  public static class ViaQuerySelectionsResolver extends ContainerResolvers.ViaQuerySelections {
    @Override
    public CompletableFuture<Integer> resolve(ContainerResolvers.ViaQuerySelections.Context ctx) {
      Query queryValue = ctx.getQueryValue();
      Integer rootValue = queryValue.getRootValueOrThrow();
      return CompletableFuture.completedFuture(rootValue != null ? rootValue : 0);
    }
  }

  @Resolver
  public static class ViaCtxQueryResolver extends ContainerResolvers.ViaCtxQuery {
    @Override
    public CompletableFuture<Integer> resolve(ContainerResolvers.ViaCtxQuery.Context ctx) {
      return ctx.query("rootValue")
          .thenApply(q -> q.getRootValueOrThrow() != null ? q.getRootValueOrThrow() : 0);
    }
  }

  @Resolver
  public static class QueryWithVariablesResolver extends ContainerResolvers.QueryWithVariables {
    @Override
    public CompletableFuture<Integer> resolve(ContainerResolvers.QueryWithVariables.Context ctx) {
      int multiplier = ctx.getArguments().getMultiplier();
      return ctx.query("multiply(n: $n)", Map.of("n", multiplier))
          .thenApply(q -> q.getMultiplyOrThrow() != null ? q.getMultiplyOrThrow() : 0);
    }
  }

  // --- User resolver ---

  @Resolver
  public static class FullNameResolver extends UserResolvers.FullName {
    @Override
    public CompletableFuture<String> resolve(UserResolvers.FullName.Context ctx) {
      return ctx.query("firstName lastName")
          .thenApply(
              q -> {
                String first = q.getFirstNameOrThrow() != null ? q.getFirstNameOrThrow() : "";
                String last = q.getLastNameOrThrow() != null ? q.getLastNameOrThrow() : "";
                return first + " " + last;
              });
    }
  }

  // --- Calculator resolver ---

  @Resolver
  public static class DoubleResolver extends CalculatorResolvers.Double {
    @Override
    public CompletableFuture<Integer> resolve(CalculatorResolvers.Double.Context ctx) {
      int input = ctx.getArguments().getInput();
      return ctx.query("multiply(n: " + input + ")")
          .thenApply(q -> q.getMultiplyOrThrow() != null ? q.getMultiplyOrThrow() : 0);
    }
  }

  // --- Level1 resolver ---

  @Resolver
  public static class Level2Resolver extends Level1Resolvers.Level2 {
    @Override
    public CompletableFuture<Level2> resolve(Level1Resolvers.Level2.Context ctx) {
      return CompletableFuture.completedFuture(Level2.builder(ctx).build());
    }
  }

  // --- Level2 resolver ---

  @Resolver
  public static class DerivedValueResolver extends Level2Resolvers.DerivedValue {
    @Override
    public CompletableFuture<Integer> resolve(Level2Resolvers.DerivedValue.Context ctx) {
      return ctx.query("baseValue")
          .thenApply(
              q -> {
                Integer baseValue = q.getBaseValueOrThrow();
                return baseValue != null ? baseValue * 3 : 0;
              });
    }
  }
}
