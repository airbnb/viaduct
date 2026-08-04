package viaduct.java.api.reflect;

import java.util.Objects;
import viaduct.java.api.types.GRT;

/**
 * Describes a GraphQL field whose unwrapped type is another GRT.
 *
 * @param <P> the GRT on which the field is defined
 * @param <T> the field's type with list and nullability wrappers removed
 */
public interface CompositeField<P extends GRT, T extends GRT> extends Field<P> {

  /** Returns the descriptor of the field's unwrapped type. */
  Type<T> getType();

  /** Creates a descriptor for a field whose unwrapped type is another GRT. */
  static <P extends GRT, T extends GRT> CompositeField<P, T> of(
      String name, Type<P> containingType, Type<T> type) {
    return new CompositeFieldDescriptor<>(name, containingType, type);
  }
}

record CompositeFieldDescriptor<P extends GRT, T extends GRT>(
    String name, Type<P> containingType, Type<T> type) implements CompositeField<P, T> {
  CompositeFieldDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(containingType, "containingType");
    Objects.requireNonNull(type, "type");
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
}
