package viaduct.java.runtime.featureapp.object_example.resolverbases;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.runtime.featureapp.object_example.grt.Person;
import viaduct.java.runtime.featureapp.object_example.grt.Query;

/**
 * Generated resolver base classes for Person type.
 *
 * <p>This simulates the codegen output for resolver base classes. In production, these would be
 * generated from the GraphQL schema.
 *
 * <p><b>Key difference from QueryResolvers:</b> This resolver operates on a non-root type (Person).
 * The object value returned by ctx.getObjectValue() is the Person instance, not the Query.
 */
public final class PersonResolvers {

  private PersonResolvers() {
    // Utility class
  }

  /**
   * Base class for Person.fullAddress resolver.
   *
   * <p>The {@code @ResolverFor} annotation specifies typeName = "Person" (not "Query") to indicate
   * this resolver operates on Person objects.
   *
   * <p>The resolver receives the Person instance via ctx.getObjectValue() and can compute a derived
   * field value from its properties.
   */
  @ResolverFor(typeName = "Person", fieldName = "fullAddress")
  public abstract static class FullAddressResolver
      implements FieldResolverBase<String, Person, Query, Arguments.None, CompositeOutput.None> {

    /**
     * Context for Person.fullAddress resolver. Provides type-safe access to the Person object value
     * via getObjectValue().
     */
    public static class Context
        implements FieldResolverBase.Context<Person, Query, Arguments.None, CompositeOutput.None> {

      private final FieldExecutionContext<Person, Query, Arguments.None, CompositeOutput.None>
          inner;

      public Context(
          FieldExecutionContext<Person, Query, Arguments.None, CompositeOutput.None> inner) {
        this.inner = inner;
      }

      /**
       * Returns the Person instance this resolver is operating on.
       *
       * @return the Person object whose fullAddress field is being resolved
       */
      @Override
      public Person getObjectValue() {
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
     * Resolves the fullAddress field value for a Person. Override this method to compute the full
     * address string from the Person's address fields.
     *
     * @param ctx the execution context - use ctx.getObjectValue() to access the Person instance
     * @return a future that completes with the computed full address string
     */
    public abstract CompletableFuture<String> resolve(Context ctx);
  }

  /**
   * Base class for Person.greeting resolver.
   *
   * <p>This resolver base class is used to test resolvers WITHOUT objectValueFragment. It serves as
   * a contrast to FullAddressResolver to verify that resolvers without required selections have
   * null objectSelectionSet.
   */
  @ResolverFor(typeName = "Person", fieldName = "greeting")
  public abstract static class GreetingResolver
      implements FieldResolverBase<String, Person, Query, Arguments.None, CompositeOutput.None> {

    /**
     * Context for Person.greeting resolver. Provides type-safe access to the Person object value
     * via getObjectValue().
     */
    public static class Context
        implements FieldResolverBase.Context<Person, Query, Arguments.None, CompositeOutput.None> {

      private final FieldExecutionContext<Person, Query, Arguments.None, CompositeOutput.None>
          inner;

      public Context(
          FieldExecutionContext<Person, Query, Arguments.None, CompositeOutput.None> inner) {
        this.inner = inner;
      }

      @Override
      public Person getObjectValue() {
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
     * Resolves the greeting field value for a Person.
     *
     * @param ctx the execution context
     * @return a future that completes with the greeting string
     */
    public abstract CompletableFuture<String> resolve(Context ctx);
  }
}
