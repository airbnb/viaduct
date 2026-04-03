package viaduct.java.api.context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.NodeCompositeOutput;

/** A generic context for resolving fields or types. */
public interface ResolverExecutionContext extends ExecutionContext {
  <T extends NodeCompositeOutput> T nodeFor(GlobalID<T> id);

  // TODO: Add globalIDStringFor() method for creating global ID strings

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
}
