package viaduct.java.runtime.featureapp.enum_example.resolverbases;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.runtime.featureapp.enum_example.grt.Query;
import viaduct.java.runtime.featureapp.enum_example.grt.Status;

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
   * Base class for Query.currentStatus resolver.
   *
   * <p>The {@code @ResolverFor} annotation is read by the bootstrapper to determine which field
   * this resolver handles.
   */
  @ResolverFor(typeName = "Query", fieldName = "currentStatus")
  public abstract static class CurrentStatus
      implements FieldResolverBase<Status, Query, Query, Arguments.None, CompositeOutput.None> {

    /**
     * Context for Query.currentStatus resolver. Provides type-safe access to object value, query
     * value, arguments, and selections.
     */
    public static class Context
        implements FieldResolverBase.Context<Query, Query, Arguments.None, CompositeOutput.None> {

      private final FieldExecutionContext<Query, Query, Arguments.None, CompositeOutput.None> inner;

      public Context(
          FieldExecutionContext<Query, Query, Arguments.None, CompositeOutput.None> inner) {
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
      public Arguments.None getArguments() {
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
     * Resolves the currentStatus field value for a single parent object. Override this method to
     * implement single-item resolution.
     *
     * @param ctx the execution context
     * @return a future that completes with the resolved value
     */
    public abstract CompletableFuture<Status> resolve(Context ctx);
  }
}
