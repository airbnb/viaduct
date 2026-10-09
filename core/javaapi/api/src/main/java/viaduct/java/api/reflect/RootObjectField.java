package viaduct.java.api.reflect;

import java.util.List;
import java.util.Objects;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.GRT;
import viaduct.java.api.types.GraphQLObject;

/**
 * Describes a non-list object field reachable from the query root.
 *
 * @param <P> the GRT on which the field is defined
 * @param <T> the field's unwrapped object type
 * @param <A> the field's generated arguments type
 */
public interface RootObjectField<P extends GRT, T extends GraphQLObject, A extends Arguments>
    extends CompositeField<P, T> {

  /** Returns the field-name path from the query root to this field. */
  List<String> getPathFromQueryRoot();

  /** Creates a descriptor for a non-list object field reachable from the query root. */
  static <P extends GRT, T extends GraphQLObject, A extends Arguments> RootObjectField<P, T, A> of(
      String name, Type<P> containingType, Type<T> type, List<String> pathFromQueryRoot) {
    return new RootObjectFieldDescriptor<>(name, containingType, type, pathFromQueryRoot);
  }
}

record RootObjectFieldDescriptor<P extends GRT, T extends GraphQLObject, A extends Arguments>(
    String name, Type<P> containingType, Type<T> type, List<String> pathFromQueryRoot)
    implements RootObjectField<P, T, A> {
  RootObjectFieldDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(containingType, "containingType");
    Objects.requireNonNull(type, "type");
    pathFromQueryRoot = List.copyOf(pathFromQueryRoot);
    if (pathFromQueryRoot.isEmpty()
        || !name.equals(pathFromQueryRoot.get(pathFromQueryRoot.size() - 1))) {
      throw new IllegalArgumentException(
          "pathFromQueryRoot must end with the field name '"
              + name
              + "', but was "
              + pathFromQueryRoot);
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Type<P> getContainingType() {
    return containingType;
  }

  @Override
  public Type<T> getType() {
    return type;
  }

  @Override
  public List<String> getPathFromQueryRoot() {
    return pathFromQueryRoot;
  }
}
