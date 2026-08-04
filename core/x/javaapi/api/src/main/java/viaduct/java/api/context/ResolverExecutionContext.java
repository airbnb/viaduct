package viaduct.java.api.context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.documents.QueryFromAnnotation;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;

/** A generic context for resolving fields or types. */
public interface ResolverExecutionContext extends ExecutionContext {
  <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id);

  /**
   * Creates a serialized GlobalID string for the given Node type and internal ID.
   *
   * <p>Use this instead of {@link #globalIDFor} when you need a serialized GlobalID string (e.g.,
   * to set the {@code id} field on a Node type builder).
   *
   * <p>Example: {@code ctx.globalIDStringFor(Type.ofClass(NodeObj.class), internalId)}
   *
   * @param type the GraphQL Node type
   * @param internalID the internal ID string
   * @param <T> the Node GRT type
   * @return the serialized GlobalID string
   */
  <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID);

  /**
   * Executes a subquery against the Query root type and returns the result as an instance of {@code
   * targetClass} with the requested selections populated.
   *
   * @param selections the GraphQL selection string (e.g., {@code "firstName lastName"})
   * @param variables variables map for parameterized selections
   * @param targetClass the Java GRT class to populate with the resolved data
   * @param <T> the Java GRT type for the Query root
   * @return a future that completes with the populated Query GRT instance
   */
  <T> CompletableFuture<T> query(
      String selections, Map<String, Object> variables, Class<T> targetClass);

  /** Executes a subquery against the Query root type with no variables. */
  default <T> CompletableFuture<T> query(String selections, Class<T> targetClass) {
    return query(selections, Map.of(), targetClass);
  }

  /**
   * Executes a query declared with {@code @GraphQLOperation}.
   *
   * <p>Generated resolver contexts provide overloads that infer {@code targetClass} from the
   * tenant's Query GRT.
   */
  default <T> CompletableFuture<T> query(
      QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
    return query(operation.getOperationText(), variables, targetClass);
  }

  /** Executes an annotated query with no variables. */
  default <T> CompletableFuture<T> query(QueryFromAnnotation operation, Class<T> targetClass) {
    return query(operation, Map.of(), targetClass);
  }

  /**
   * Executes a sub-mutation against the Mutation root type and returns the result as an instance of
   * {@code targetClass} with the requested selections populated.
   *
   * @param selections the GraphQL selection string (e.g., {@code "incrementCounter"})
   * @param variables variables map for parameterized selections
   * @param targetClass the Java GRT class to populate with the resolved data
   * @param <T> the Java GRT type for the Mutation root
   * @return a future that completes with the populated Mutation GRT instance
   */
  <T> CompletableFuture<T> mutation(
      String selections, Map<String, Object> variables, Class<T> targetClass);

  /** Executes a sub-mutation against the Mutation root type with no variables. */
  default <T> CompletableFuture<T> mutation(String selections, Class<T> targetClass) {
    return mutation(selections, Map.of(), targetClass);
  }

  /**
   * Executes a mutation declared with {@code @GraphQLOperation}.
   *
   * <p>Generated resolver contexts provide overloads that infer {@code targetClass} from the
   * tenant's Mutation GRT.
   */
  default <T> CompletableFuture<T> mutation(
      MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
    return mutation(operation.getOperationText(), variables, targetClass);
  }

  /** Executes an annotated mutation with no variables. */
  default <T> CompletableFuture<T> mutation(
      MutationFromAnnotation operation, Class<T> targetClass) {
    return mutation(operation, Map.of(), targetClass);
  }
}
