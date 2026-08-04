package viaduct.java.api.documents;

import viaduct.java.api.annotations.GraphQLOperation;

/** Base class for reusable, build-time validated GraphQL mutation operations. */
public abstract class MutationFromAnnotation {
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
