package viaduct.java.runtime.featureapp.mutationcapability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.GraphQLOperation;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.context.MutationFieldExecutionContext;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.runtime.featureapp.mutationcapability.resolverbases.DeepOperationsResolvers;
import viaduct.java.runtime.featureapp.mutationcapability.resolverbases.MutationResolvers;
import viaduct.java.runtime.featureapp.mutationcapability.resolverbases.NodeResolvers;
import viaduct.java.runtime.featureapp.mutationcapability.resolverbases.PayloadResolvers;
import viaduct.java.runtime.featureapp.mutationcapability.resolverbases.QueryResolvers;
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault;

@SuppressWarnings("deprecation")
public class JavaMutationCapabilityContractTest extends MutationCapabilityContract {
  private static final AtomicInteger counter = new AtomicInteger();

  @GraphQLOperation("mutation { increment }")
  public static final class Increment extends MutationFromAnnotation {}

  @BeforeEach
  void resetCounter() {
    counter.set(0);
  }

  @Override
  protected String featureAppPackagePrefix() {
    return getClass().getPackageName();
  }

  @Test
  void rootAndNamespaceResolversCanExecuteNestedMutations() {
    var result = execute("mutation { nested operations { deeper { run } } }");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getData())
        .isEqualTo(Map.of("nested", 1, "operations", Map.of("deeper", Map.of("run", 2))));
    assertThat(counter.get()).isEqualTo(2);
  }

  @Test
  void queryFieldsRejectBothMutationOverloadsWithoutSideEffects() {
    var result = execute("{ attemptString attemptAnnotated }");

    assertThat(result.getErrors())
        .hasSize(2)
        .allSatisfy(
            error ->
                assertThat(error.getMessage())
                    .contains("only available in mutation field resolvers"));
    assertThat(counter.get()).isZero();
  }

  @Test
  void nodeResolversCannotExecuteMutations() {
    String id = GlobalIDCodecDefault.INSTANCE.serialize("UnsafeNode", "1");
    var result = execute("{ node(id: \"" + id + "\") { ... on UnsafeNode { value } } }");

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().get(0).getMessage())
        .contains("only available in mutation field resolvers");
    assertThat(counter.get()).isZero();
  }

  @Test
  void ordinaryPayloadFieldsCannotExecuteMutationsEvenInsideAMutation() {
    var result = execute("mutation { payload { attempt } }");

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().get(0).getMessage())
        .contains("only available in mutation field resolvers");
    assertThat(counter.get()).isZero();
  }

  private static CompletableFuture<Integer> increment(
      MutationFieldExecutionContext<?, ?, ?, ?> ctx) {
    return ctx.mutation(new Increment(), Mutation.class).thenApply(Mutation::getIncrementOrThrow);
  }

  @Resolver
  public static class IncrementResolver extends MutationResolvers.Increment {
    @Override
    public CompletableFuture<Integer> resolve(MutationResolvers.Increment.Context ctx) {
      return CompletableFuture.completedFuture(counter.incrementAndGet());
    }
  }

  @Resolver
  public static class NestedResolver extends MutationResolvers.Nested {
    @Override
    public CompletableFuture<Integer> resolve(MutationResolvers.Nested.Context ctx) {
      return increment(ctx);
    }
  }

  @Resolver
  public static class NamespaceResolver extends DeepOperationsResolvers.Run {
    @Override
    public CompletableFuture<Integer> resolve(DeepOperationsResolvers.Run.Context ctx) {
      return increment(ctx);
    }
  }

  @Resolver
  public static class StringAttemptResolver extends QueryResolvers.AttemptString {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.AttemptString.Context ctx) {
      return ctx.mutation("increment").thenApply(Mutation::getIncrementOrThrow);
    }
  }

  @Resolver
  public static class AnnotatedAttemptResolver extends QueryResolvers.AttemptAnnotated {
    @Override
    public CompletableFuture<Integer> resolve(QueryResolvers.AttemptAnnotated.Context ctx) {
      return ctx.mutation(new Increment()).thenApply(Mutation::getIncrementOrThrow);
    }
  }

  @Resolver
  public static class PayloadResolver extends MutationResolvers.Payload {
    @Override
    public CompletableFuture<Payload> resolve(MutationResolvers.Payload.Context ctx) {
      return CompletableFuture.completedFuture(Payload.builder(ctx).build());
    }
  }

  @Resolver
  public static class PayloadAttemptResolver extends PayloadResolvers.Attempt {
    @Override
    public CompletableFuture<Integer> resolve(PayloadResolvers.Attempt.Context ctx) {
      return ctx.mutation("increment", Mutation.class).thenApply(Mutation::getIncrementOrThrow);
    }
  }

  @Resolver
  public static class UnsafeNodeResolver extends NodeResolvers.UnsafeNode {
    @Override
    public CompletableFuture<UnsafeNode> resolve(NodeResolvers.UnsafeNode.Context ctx) {
      return ctx.mutation(new Increment(), Mutation.class)
          .thenApply(ignored -> UnsafeNode.builder(ctx).id(ctx.getId()).build());
    }
  }
}
