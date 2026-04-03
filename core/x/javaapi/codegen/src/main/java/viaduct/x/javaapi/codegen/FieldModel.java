package viaduct.x.javaapi.codegen;

import java.util.Set;

/** Model representing a GraphQL field for code generation. */
public record FieldModel(String name, String javaType, boolean nullable) {

  private static final Set<String> JAVA_KEYWORDS =
      Set.of(
          "abstract",
          "assert",
          "boolean",
          "break",
          "byte",
          "case",
          "catch",
          "char",
          "class",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "goto",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "native",
          "new",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "strictfp",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "transient",
          "try",
          "void",
          "volatile",
          "while",
          "true",
          "false",
          "null");

  // ST (StringTemplate) requires JavaBean-style getters
  public String getName() {
    return name;
  }

  /**
   * Returns a Java-safe identifier name. If the field name is a Java reserved keyword, appends
   * {@code _} to avoid compilation errors (e.g., {@code double} → {@code double_}).
   */
  public String getSafeName() {
    return JAVA_KEYWORDS.contains(name) ? name + "_" : name;
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
