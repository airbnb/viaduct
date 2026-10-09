package viaduct.java.api.reflect;

import java.util.Objects;
import viaduct.java.api.types.GRT;

/**
 * Describes static properties of a GraphQL field.
 *
 * @param <P> the GRT on which the field is defined
 */
public interface Field<P extends GRT> {

  /** Returns the GraphQL name of this field. */
  String getName();

  /** Returns the descriptor of the type on which this field is defined. */
  Type<P> getContainingType();

  /** Creates a descriptor for a scalar field. */
  static <P extends GRT> Field<P> of(String name, Type<P> containingType) {
    return new FieldDescriptor<>(name, containingType);
  }
}

record FieldDescriptor<P extends GRT>(String name, Type<P> containingType) implements Field<P> {
  FieldDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(containingType, "containingType");
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Type<P> getContainingType() {
    return containingType;
  }
}
