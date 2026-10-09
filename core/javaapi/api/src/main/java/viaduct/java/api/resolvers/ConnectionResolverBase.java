package viaduct.java.api.resolvers;

import viaduct.java.api.context.ConnectionFieldExecutionContext;
import viaduct.java.api.types.Connection;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.Query;

/**
 * Base interface for connection field resolver implementations in the Java Tenant API.
 *
 * <p>Java equivalent of Kotlin's {@code ConnectionResolverBase}. Narrows {@link FieldResolverBase}
 * so a connection resolver's arguments are typed as {@link ConnectionArguments} and its output as a
 * {@link Connection}. Generated connection resolver base classes implement this interface.
 *
 * @param <T> the return type of the resolver (the connection GraphQL type)
 * @param <O> the parent object type containing this field
 * @param <Q> the Query root type
 * @param <A> the connection-arguments type for this field
 * @param <R> the connection output type for the selections
 */
public interface ConnectionResolverBase<
        T,
        O extends GraphQLObject,
        Q extends Query,
        A extends ConnectionArguments,
        R extends Connection<?, ?>>
    extends FieldResolverBase<T, O, Q, A, R> {

  /** Context type alias for connection resolvers, narrowing to {@link ConnectionArguments}. */
  interface Context<
          O extends GraphQLObject,
          Q extends Query,
          A extends ConnectionArguments,
          R extends Connection<?, ?>>
      extends ConnectionFieldExecutionContext<O, Q, A, R> {}
}
