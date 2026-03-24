package viaduct.x.javaapi.codegen;

/** Model representing a GraphQL field for code generation. */
public record FieldModel(String name, String javaType, boolean nullable) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getName() {
    return name;
  }

  public String getJavaType() {
    return javaType;
  }

  public boolean getNullable() {
    return nullable;
  }

  /** Returns the getter method name for this field. */
  public String getGetterName() {
    return "get" + capitalize(name);
  }

  /** Returns the setter method name for this field. */
  public String getSetterName() {
    return "set" + capitalize(name);
  }

  private static String capitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
