package viaduct.java.runtime.example.resolverbases;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
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
  @ResolverFor(typeName = "Query", fieldName = "greeting", isSelective = false)
  public abstract static class Greeting
      implements FieldResolverBase<String, Query, Query, Arguments.None, CompositeOutput.None>,
          BaseUnbatchedFieldResolver {

    /**
     * Context for Query.greeting resolver. Provides type-safe access to object value, query value,
     * arguments, and selections.
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
      public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
        return inner.globalIDStringFor(type, internalID);
      }

      @Override
      public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
        return inner.nodeRef(id);
      }

      @Override
      public <T> CompletableFuture<T> query(
          String selections, Map<String, Object> variables, Class<T> targetClass) {
        return inner.query(selections, variables, targetClass);
      }

      @Override
      public <T> CompletableFuture<T> mutation(
          String selections, Map<String, Object> variables, Class<T> targetClass) {
        return inner.mutation(selections, variables, targetClass);
      }
    }

    /**
     * Resolves the greeting field value for a single parent object. Override this method to
     * implement single-item resolution.
     *
     * @param ctx the execution context
     * @return a future that completes with the resolved value
     */
    public abstract CompletableFuture<String> resolve(Context ctx);

    @Override
    @SuppressWarnings("unchecked")
    public final CompletableFuture<?> invokeFieldResolver(
        FieldExecutionContext<?, ?, ?, ?> context) {
      return resolve(
          new Context(
              (FieldExecutionContext<Query, Query, Arguments.None, CompositeOutput.None>) context));
    }
  }
}
