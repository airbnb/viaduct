package viaduct.java.runtime.example.resolverbases;

import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.runtime.example.grts.Query;

/**
 * Generated resolver base classes for Query type.
 *
 * <p>This simulates the codegen output for resolver base classes. In a real application, these
 * would be generated from the GraphQL schema.
 */
public final class QueryResolvers {

  private QueryResolvers() {}

  /**
   * Base class for Query.greeting resolver.
   *
   * <p>The {@code @ResolverFor} annotation is read by the bootstrapper to determine which field
   * this resolver handles.
   */
  @ResolverFor(typeName = "Query", fieldName = "greeting")
  public abstract static class Greeting
      implements FieldResolverBase<String, Query, Query, Arguments.None, CompositeOutput> {}
}
