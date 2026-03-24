package viaduct.java.api.types;

/** Tagging interface for GraphQL enum types. */
public interface GraphQLEnum extends GRT {

  /**
   * Convert a string value to an enum constant. This is a helper method for working with generated
   * enum types that implement this interface.
   *
   * @param clazz The enum class
   * @param value The string value of the enum constant
   * @param <T> The enum type that implements both java.lang.Enum and viaduct.java.api.types.Enum
   * @return The enum constant with the given name
   * @throws IllegalArgumentException if no enum constant with the given name exists
   */
  static <T extends java.lang.Enum<T> & GraphQLEnum> T enumFrom(Class<T> clazz, String value) {
    try {
      return java.lang.Enum.valueOf(clazz, value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "No enum constant " + clazz.getSimpleName() + "." + value, e);
    }
  }
}
