package viaduct.java.runtime.example;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.example.resolverbases.QueryResolvers;

/**
 * A simple "Hello World" resolver that returns a greeting message.
 *
 * <p>This resolver demonstrates the simplest possible Java resolver:
 *
 * <ul>
 *   <li>Resolves Query.greeting field (inherited from base class)
 *   <li>Returns a String (scalar type)
 *   <li>No required selection sets
 *   <li>No arguments
 * </ul>
 *
 * <p>The base class {@link QueryResolvers.Greeting} is annotated with {@code @ResolverFor} which
 * tells the bootstrapper which field this resolver handles.
 */
@Resolver
public class GreetingResolver extends QueryResolvers.Greeting {

  /**
   * Resolves the greeting field.
   *
   * @param ctx the execution context (not used in this simple example)
   * @return a CompletableFuture containing the greeting message
   */
  @Override
  public CompletableFuture<String> resolve(Context ctx) {
    return CompletableFuture.completedFuture("Hello, World!");
  }
}
