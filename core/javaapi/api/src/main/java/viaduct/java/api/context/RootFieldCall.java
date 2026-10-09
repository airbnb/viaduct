package viaduct.java.api.context;

import viaduct.apiannotations.ExperimentalApi;
import viaduct.apiannotations.InternalApi;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.GraphQLObject;

/**
 * Describes a root field and the arguments used to call it.
 *
 * <p>Generated root field methods return this value without executing the field. A resolver passes
 * it to {@link ResolverExecutionContext#ref} to create a lazy reference that the engine resolves
 * later.
 *
 * @param <T> the field's object output type
 */
@ExperimentalApi
public interface RootFieldCall<T extends GraphQLObject> {

  @InternalApi
  RootObjectField<?, T, ? extends Arguments> field();

  /**
   * Builds the arguments the field is called with, using {@code context} to convert input values.
   */
  @InternalApi
  Arguments arguments(ExecutionContext context);
}
