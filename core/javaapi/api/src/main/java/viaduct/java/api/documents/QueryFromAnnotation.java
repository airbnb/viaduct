package viaduct.java.api.documents;

import viaduct.apiannotations.StableApi;
import viaduct.java.api.annotations.GraphQLOperation;

/** Base class for reusable, build-time validated GraphQL query operations. */
@StableApi
public abstract class QueryFromAnnotation {
  /** Returns the operation document declared by {@link GraphQLOperation}. */
  public final String getOperationText() {
    GraphQLOperation annotation = getClass().getAnnotation(GraphQLOperation.class);
    if (annotation == null) {
      throw new IllegalStateException(
          getClass().getSimpleName() + " must be annotated with @GraphQLOperation");
    }
    return annotation.value();
  }
}
