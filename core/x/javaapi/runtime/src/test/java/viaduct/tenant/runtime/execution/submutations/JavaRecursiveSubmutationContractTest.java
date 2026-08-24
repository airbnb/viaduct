package viaduct.tenant.runtime.execution.submutations;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.submutations.resolverbases.MutationResolvers;

public class JavaRecursiveSubmutationContractTest extends RecursiveSubmutationContractTest {

  // --- Resolvers ---

  @Resolver
  public static class ExampleMutationSelectionsResolver
      extends MutationResolvers.ExampleMutationSelections {
    @Override
    public CompletableFuture<Integer> resolve(Context ctx) {
      int triangleSize = ctx.getArguments().getTriangleSize();
      if (triangleSize <= 1) {
        return CompletableFuture.completedFuture(1);
      }
      int next = triangleSize - 1;
      return ctx.mutation(
              "exampleMutationSelections(triangleSize: $n)", Map.of("n", next), Mutation.class)
          .thenApply(result -> triangleSize + result.getExampleMutationSelectionsOrThrow());
    }
  }
}
