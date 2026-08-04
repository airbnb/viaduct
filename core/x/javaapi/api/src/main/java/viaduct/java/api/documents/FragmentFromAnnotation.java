package viaduct.java.api.documents;

import viaduct.java.api.annotations.GraphQLFragment;
import viaduct.java.api.types.CompositeOutput;

/**
 * Base class for reusable named GraphQL fragments.
 *
 * <p>Subclasses must be annotated with {@link GraphQLFragment}. The type parameter identifies the
 * GraphQL composite type selected by the fragment and is checked against the fragment's type
 * condition during tenant-module assembly.
 *
 * @param <T> the GraphQL composite output type selected by the fragment
 */
public abstract class FragmentFromAnnotation<T extends CompositeOutput> {
  /** Returns the fragment document declared by {@link GraphQLFragment}. */
  public final String getFragmentText() {
    GraphQLFragment annotation = getClass().getAnnotation(GraphQLFragment.class);
    if (annotation == null) {
      throw new IllegalStateException(
          getClass().getSimpleName() + " must be annotated with @GraphQLFragment");
    }
    return annotation.value();
  }
}
