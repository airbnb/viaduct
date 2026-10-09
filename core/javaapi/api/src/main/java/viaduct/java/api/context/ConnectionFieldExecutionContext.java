package viaduct.java.api.context;

import viaduct.java.api.types.Connection;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.Query;

/**
 * A {@link FieldExecutionContext} provided to resolvers of connection fields.
 *
 * <p>Narrows the arguments type to {@link ConnectionArguments} and the output type to {@link
 * Connection}, so a connection resolver's {@code ctx.getArguments()} is typed with the pagination
 * inputs. Java equivalent of Kotlin's {@code viaduct.api.context.ConnectionFieldExecutionContext}.
 *
 * @param <T> the type of the object on which this field is being resolved
 * @param <Q> the type of the Query root object
 * @param <A> the connection-arguments type for this field
 * @param <R> the connection output type
 */
public interface ConnectionFieldExecutionContext<
        T extends GraphQLObject,
        Q extends Query,
        A extends ConnectionArguments,
        R extends Connection<?, ?>>
    extends FieldExecutionContext<T, Q, A, R> {}
