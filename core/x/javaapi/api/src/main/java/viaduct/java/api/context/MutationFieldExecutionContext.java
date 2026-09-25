package viaduct.java.api.context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.Query;

/** Mutation capability for fields on the mutation root or its reachable namespace types. */
@SuppressWarnings("deprecation")
public interface MutationFieldExecutionContext<
        T extends GraphQLObject, Q extends Query, A extends Arguments, O extends CompositeOutput>
    extends FieldExecutionContext<T, Q, A, O> {
  @Override
  <R> CompletableFuture<R> mutation(
      String selections, Map<String, Object> variables, Class<R> targetClass);

  @Override
  default <R> CompletableFuture<R> mutation(String selections, Class<R> targetClass) {
    return mutation(selections, Map.of(), targetClass);
  }

  @Override
  default <R> CompletableFuture<R> mutation(
      MutationFromAnnotation operation, Map<String, Object> variables, Class<R> targetClass) {
    return mutation(operation.getOperationText(), variables, targetClass);
  }

  @Override
  default <R> CompletableFuture<R> mutation(
      MutationFromAnnotation operation, Class<R> targetClass) {
    return mutation(operation, Map.of(), targetClass);
  }
}
