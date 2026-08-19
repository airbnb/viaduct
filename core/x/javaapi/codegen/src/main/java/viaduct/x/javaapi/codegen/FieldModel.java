package viaduct.x.javaapi.codegen;

import java.util.List;
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
    boolean globalIDType,
    String baseTypeName,
    String reflectedTypeName,
    boolean rootObjectField,
    String argumentsTypeName,
    List<String> pathFromQueryRoot) {

  public FieldModel {
    pathFromQueryRoot = pathFromQueryRoot == null ? null : List.copyOf(pathFromQueryRoot);
  }

  /** Legacy constructor for fields without reflection metadata. */
  public FieldModel(
      String name,
      String javaType,
      boolean nullable,
      boolean compositeType,
      boolean list,
      boolean enumType,
      boolean abstractType,
      boolean globalIDType,
      String baseTypeName) {
    this(
        name,
        javaType,
        nullable,
        compositeType,
        list,
        enumType,
        abstractType,
        globalIDType,
        baseTypeName,
        null,
        false,
        null,
        null);
  }

  /**
   * Creates a simple scalar FieldModel with no composite/enum/abstract type metadata. Useful for
   * tests and scalar fields.
   */
  public static FieldModel simple(String name, String javaType, boolean nullable) {
    return new FieldModel(
        name, javaType, nullable, false, false, false, false, false, null, null, false, null, null);
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

  /** Returns whether this field is a GlobalID type (has @idOf directive or is Node.id). */
  public boolean getGlobalIDType() {
    return globalIDType;
  }

  /** Returns true if this is a list of GlobalID values. Used for ST template conditionals. */
  public boolean getGlobalIDList() {
    return globalIDType && list;
  }

  /** Returns the simple class name of the base type (for composite and enum fields). */
  public String getBaseTypeName() {
    return baseTypeName;
  }

  /** Returns whether generated builders should pass a generated type token for this field. */
  public boolean getHasGeneratedType() {
    return compositeType || enumType || abstractType;
  }

  /** Returns whether this field's unwrapped type has a generated reflection descriptor. */
  public boolean getHasReflectedType() {
    return reflectedTypeName != null;
  }

  /** Returns the simple name of this field's reflected, unwrapped type. */
  public String getReflectedTypeName() {
    return reflectedTypeName;
  }

  /** Returns whether this field is a non-list object field reachable from the query root. */
  public boolean getRootObjectField() {
    return rootObjectField;
  }

  /** Returns the generated arguments type for a root-object field. */
  public String getArgumentsTypeName() {
    return argumentsTypeName;
  }

  /** Returns the path segments from the query root to this root-object field. */
  public List<String> getPathFromQueryRoot() {
    return pathFromQueryRoot;
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
   * Returns true if this is a list of scalar values (not composites, not enums, not GlobalID). Used
   * for ST template conditionals.
   */
  public boolean getScalarList() {
    return !compositeType && !enumType && !globalIDType && list;
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

  /**
   * Returns the type to use in builder setter methods. GlobalID fields accept typed GlobalID
   * values; the builder serializes them to the wire format before storing in the data map.
   */
  public String getBuilderType() {
    return javaType;
  }

  /** Returns true if this field needs GlobalID serialization in the builder (single value). */
  public boolean getGlobalIDBuilderSerialize() {
    return globalIDType && !list;
  }

  /** Returns true if this field needs GlobalID list serialization in the builder. */
  public boolean getGlobalIDListBuilderSerialize() {
    return globalIDType && list;
  }

  /**
   * Returns the expression used to read this field from an ObjectBase. Both ordinary and connection
   * object templates consume this so their generated getters cannot drift.
   */
  public String getGetterExpression() {
    String fieldName = "\"" + name + "\"";
    if (getGlobalIDList()) {
      return "fetchGlobalIDList(" + fieldName + ")";
    }
    if (globalIDType) {
      return "fetchGlobalID(" + fieldName + ")";
    }
    if (getAbstractList()) {
      return "fetchAbstractObjectList(" + fieldName + ", " + baseTypeName + ".class)";
    }
    if (abstractType) {
      return "fetchAbstractObject(" + fieldName + ", " + baseTypeName + ".class)";
    }
    if (getCompositeList()) {
      return "fetchObjectList(" + fieldName + ", " + baseTypeName + "::new)";
    }
    if (compositeType) {
      return "fetchObject(" + fieldName + ", " + baseTypeName + "::new)";
    }
    if (getEnumList()) {
      return "fetchEnumList(" + fieldName + ", " + baseTypeName + ".class)";
    }
    if (enumType) {
      return "fetchEnum(" + fieldName + ", " + baseTypeName + ".class)";
    }
    if (getTemporalScalarList()) {
      return "fetchScalarList(" + fieldName + ", \"" + getScalarCoercionHint() + "\")";
    }
    if (getTemporalScalar()) {
      return "fetchScalar(" + fieldName + ", \"" + getScalarCoercionHint() + "\")";
    }
    if (getScalarList()) {
      return "fetchScalarList(" + fieldName + ")";
    }
    return "fetchScalar(" + fieldName + ")";
  }

  /**
   * Returns the expression stored by a generated builder setter. GlobalIDs are converted to their
   * engine-space wire representation; every other field is stored unchanged.
   */
  public String getBuilderValueExpression() {
    return builderValueExpression(getSafeName());
  }

  private String builderValueExpression(String value) {
    if (getGlobalIDBuilderSerialize()) {
      return value
          + " == null ? null : __context.getGlobalIDCodec().serialize("
          + value
          + ".getType().getName(), "
          + value
          + ".getInternalID())";
    }
    if (getGlobalIDListBuilderSerialize()) {
      return value
          + " == null ? null : "
          + value
          + ".stream().map(__id -> __id == null ? null : __context.getGlobalIDCodec().serialize("
          + "__id.getType().getName(), __id.getInternalID()))"
          + ".collect(java.util.stream.Collectors.toList())";
    }
    return value;
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
