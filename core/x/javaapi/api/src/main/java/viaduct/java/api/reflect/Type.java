package viaduct.java.api.reflect;

import viaduct.java.api.types.GRT;

/**
 * A Type describes static properties of a GraphQL type.
 *
 * @param <T> The GRT (GraphQL Runtime Type) that this Type represents
 */
public interface Type<T extends GRT> {

  /**
   * @return The GraphQL name of this type
   */
  String getName();

  /**
   * @return The Java Class that describes values for this type
   */
  Class<? extends T> getJavaClass();

  /**
   * Create a Type from the provided Class.
   *
   * @param cls The Java class representing the GraphQL type
   * @param <T> The GRT type parameter
   * @return A Type instance for the given class
   */
  static <T extends GRT> Type<T> ofClass(Class<T> cls) {
    if (!GRT.class.isAssignableFrom(cls)) {
      throw new IllegalArgumentException("Class must implement GRT: " + cls);
    }
    return new Type<>() {
      @Override
      public String getName() {
        return cls.getSimpleName();
      }

      @Override
      public Class<? extends T> getJavaClass() {
        return cls;
      }

      @Override
      public boolean equals(Object other) {
        if (!(other instanceof Type<?> otherType)) {
          return false;
        }
        return getName().equals(otherType.getName())
            && getJavaClass().equals(otherType.getJavaClass());
      }

      @Override
      public int hashCode() {
        int result = getName().hashCode();
        result = 31 * result + getJavaClass().hashCode();
        return result;
      }

      @Override
      public String toString() {
        return "Type(" + getName() + ")";
      }
    };
  }
}
