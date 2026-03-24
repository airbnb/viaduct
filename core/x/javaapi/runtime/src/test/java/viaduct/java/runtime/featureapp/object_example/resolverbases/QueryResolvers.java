package viaduct.java.runtime.featureapp.object_example.resolverbases;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.runtime.featureapp.object_example.grt.Person;
import viaduct.java.runtime.featureapp.object_example.grt.Query;
import viaduct.java.runtime.featureapp.object_example.grt.Query_person_Arguments;

/**
 * Generated resolver base classes for Query type.
 *
 * <p>This simulates the codegen output for resolver base classes. In production, these would be
 * generated from the GraphQL schema.
 */
public final class QueryResolvers {

  private QueryResolvers() {
    // Utility class
  }

  /**
   * Base class for Query.person resolver.
   *
   * <p>The {@code @ResolverFor} annotation is read by the bootstrapper to determine which field
   * this resolver handles.
   *
   * <p>Note: Uses Query_person_Arguments to demonstrate typed arguments with a name parameter.
   */
  @ResolverFor(typeName = "Query", fieldName = "person")
  public abstract static class PersonResolver
      implements FieldResolverBase<
          Person, Query, Query, Query_person_Arguments, CompositeOutput.None> {

    /**
     * Context for Query.person resolver. Provides type-safe access to object value, query value,
     * arguments, and selections.
     */
    public static class Context
        implements FieldResolverBase.Context<
            Query, Query, Query_person_Arguments, CompositeOutput.None> {

      private final FieldExecutionContext<
              Query, Query, Query_person_Arguments, CompositeOutput.None>
          inner;

      public Context(
          FieldExecutionContext<Query, Query, Query_person_Arguments, CompositeOutput.None> inner) {
        this.inner = inner;
      }

      @Override
      public Query getObjectValue() {
        return inner.getObjectValue();
      }

      @Override
      public Query getQueryValue() {
        return inner.getQueryValue();
      }

      @Override
      public Query_person_Arguments getArguments() {
        return inner.getArguments();
      }

      @Override
      public Object getSelections() {
        return inner.getSelections();
      }

      @Override
      public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(
          Type<T> type, String internalID) {
        return inner.globalIDFor(type, internalID);
      }

      @Override
      public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
        return inner.serialize(globalID);
      }

      @Override
      public Object getRequestContext() {
        return inner.getRequestContext();
      }

      @Override
      public <T extends NodeCompositeOutput> T nodeFor(GlobalID<T> id) {
        return inner.nodeFor(id);
      }
    }

    /**
     * Resolves the person field value for a single parent object. Override this method to implement
     * single-item resolution.
     *
     * @param ctx the execution context containing typed arguments
     * @return a future that completes with the resolved Person value
     */
    public abstract CompletableFuture<Person> resolve(Context ctx);
  }
}
