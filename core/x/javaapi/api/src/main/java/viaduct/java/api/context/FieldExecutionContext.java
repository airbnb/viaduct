package viaduct.java.api.context;

import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.Query;

/**
 * An {@link ExecutionContext} provided to field resolvers.
 *
 * @param <T> The type of the object on which this field is being resolved
 * @param <Q> The type of the Query root object
 * @param <A> The type of the arguments for this field
 * @param <O> The type of the output/selections for this field
 */
public interface FieldExecutionContext<
        T extends GraphQLObject, Q extends Query, A extends Arguments, O extends CompositeOutput>
    extends ResolverExecutionContext {

  /**
   * A value of type {@code T}, with any (and only) selections from the resolver's object value
   * fragment populated. Attempting to access fields not declared in the object value fragment will
   * throw a runtime exception.
   *
   * @return The object value with requested selections populated
   */
  T getObjectValue();

  /**
   * A value of type {@code Q}, with any (and only) selections from the resolver's query value
   * fragment populated. Attempting to access fields not declared in the query value fragment will
   * throw a runtime exception.
   *
   * @return The query value with requested selections populated
   */
  Q getQueryValue();

  /**
   * The value of any arguments that were provided by the caller of this resolver. If this field
   * does not take arguments, this returns {@link Arguments#NoArguments}.
   *
   * @return The arguments provided to this field
   */
  A getArguments();

  /**
   * Get the selection set that the caller provided for the output of this field. If this field does
   * not have a selection set (i.e. it has a scalar or enum type), this would return a "no
   * selections" marker.
   *
   * @return The selections requested by the caller
   */
  // TODO: Return type should be SelectionSet<O> once that class is implemented
  Object getSelections();
}
