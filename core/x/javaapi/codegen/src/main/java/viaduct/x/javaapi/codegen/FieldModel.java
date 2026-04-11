package viaduct.x.javaapi.codegen;

import java.util.Set;

/** Model representing a GraphQL field for code generation. */
public record FieldModel(
    String name,
    String javaType,
    boolean nullable,
    boolean compositeType,
    boolean list,
    boolean enumType,
    boolean abstractType,
    String baseTypeName) {

  /**
   * Creates a simple scalar FieldModel with no composite/enum/abstract type metadata. Useful for
   * tests and scalar fields.
   */
  public static FieldModel simple(String name, String javaType, boolean nullable) {
    return new FieldModel(name, javaType, nullable, false, false, false, false, null);
  }

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
          "null",
          // '_' is a reserved identifier in Java 9+ and cannot be used as a standalone identifier
          "_");

  // ST (StringTemplate) requires JavaBean-style getters
  public String getName() {
    return name;
  }

  /**
   * Returns a Java-safe identifier name. If the field name is a Java reserved keyword or reserved
   * identifier, appends {@code _} to avoid compilation errors (e.g., {@code double} → {@code
   * double_}, {@code _} → {@code __}).
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

  /** Returns whether this field's base type is a composite object or nested input type. */
  public boolean getCompositeType() {
    return compositeType;
  }

  /** Returns whether this field is a list type. */
  public boolean getList() {
    return list;
  }

  /** Returns whether this field's base type is an enum. */
  public boolean getEnumType() {
    return enumType;
  }

  /** Returns whether this field's base type is an interface or union (abstract composite type). */
  public boolean getAbstractType() {
    return abstractType;
  }

  /** Returns the simple class name of the base type (for composite and enum fields). */
  public String getBaseTypeName() {
    return baseTypeName;
  }

  /** Returns true if this is a list of composite objects. Used for ST template conditionals. */
  public boolean getCompositeList() {
    return compositeType && list;
  }

  /**
   * Returns true if this is a list of abstract composite types (interfaces/unions). Used for ST
   * template conditionals.
   */
  public boolean getAbstractList() {
    return abstractType && list;
  }

  /** Returns true if this is a list of enums. Used for ST template conditionals. */
  public boolean getEnumList() {
    return enumType && list;
  }

  /**
   * Returns true if this is a list of scalar values (not composites, not enums). Used for ST
   * template conditionals.
   */
  public boolean getScalarList() {
    return !compositeType && !enumType && list;
  }

  /**
   * Returns the GraphQL scalar type name for temporal types that need coercion at runtime, or null
   * for non-temporal scalars. Maps Java types back to their GraphQL scalar names: Instant →
   * "DateTime", LocalDate → "Date", OffsetTime → "Time".
   */
  public String getScalarCoercionHint() {
    if (compositeType || enumType || abstractType) return null;
    String baseType = javaType;
    if (list && javaType.startsWith("List<") && javaType.endsWith(">")) {
      baseType = javaType.substring(5, javaType.length() - 1);
    }
    return switch (baseType) {
      case "Instant" -> "DateTime";
      case "LocalDate" -> "Date";
      case "OffsetTime" -> "Time";
      default -> null;
    };
  }

  /** Returns true if this field needs temporal scalar coercion. */
  public boolean getHasScalarCoercionHint() {
    return getScalarCoercionHint() != null;
  }

  /** Returns true if this is a non-list temporal scalar field needing coercion. */
  public boolean getTemporalScalar() {
    return getHasScalarCoercionHint() && !list;
  }

  /** Returns true if this is a list of temporal scalar values needing coercion. */
  public boolean getTemporalScalarList() {
    return getHasScalarCoercionHint() && list;
  }

  /** Returns the getter method name for this field. */
  public String getGetterName() {
    return "get" + capitalize(name);
  }

  private static String capitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
