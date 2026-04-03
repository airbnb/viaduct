package viaduct.x.javaapi.codegen;

import viaduct.graphql.schema.ViaductSchema;

/**
 * Maps GraphQL types to Java types using ViaductSchema.TypeExpr. TypeExpr provides: - baseTypeDef:
 * the base type (String, Int, User, etc.) - baseTypeNullable: is the base type nullable? -
 * listDepth: depth of list nesting (0 = not a list, 1 = List<X>, 2 = List<List<X>>) -
 * nullableAtDepth(i): is the list at depth i nullable?
 */
public class TypeMapper {

  private final String grtPackage;

  /** Creates a TypeMapper that returns simple type names for custom types. */
  public TypeMapper() {
    this.grtPackage = null;
  }

  /**
   * Creates a TypeMapper that returns fully qualified names for custom types. Use this when
   * generating code in a package different from the GRT package (e.g., resolver base classes).
   *
   * @param grtPackage the package name of the GRT types (e.g., {@code "com.example.grt"})
   */
  public TypeMapper(String grtPackage) {
    this.grtPackage = grtPackage;
  }

  /**
   * Maps a ViaductSchema.TypeExpr to its Java representation.
   *
   * @param typeExpr the type expression
   * @return the Java type string
   */
  public String toJavaType(ViaductSchema.TypeExpr<?> typeExpr) {
    return toJavaType(typeExpr, false);
  }

  /**
   * Maps a ViaductSchema.TypeExpr to its Java representation, always using boxed types for
   * primitives. Use this when the type will appear as a generic type parameter (e.g., inside
   * CompletableFuture or FieldResolverBase).
   *
   * @param typeExpr the type expression
   * @return the boxed Java type string
   */
  public String toBoxedJavaType(ViaductSchema.TypeExpr<?> typeExpr) {
    return toJavaType(typeExpr, true);
  }

  private String toJavaType(ViaductSchema.TypeExpr<?> typeExpr, boolean insideGeneric) {
    if (typeExpr.isList()) {
      // Unwrap one level of list and get the inner type
      ViaductSchema.TypeExpr<?> innerType = typeExpr.unwrapList();
      // Elements inside List<> must use boxed types, not primitives
      String elementType = toJavaType(innerType, true);
      return "List<" + elementType + ">";
    }

    // Base type (not a list)
    String baseTypeName = typeExpr.getBaseTypeDef().getName();
    boolean nullable = typeExpr.getBaseTypeNullable();
    return mapScalarOrCustomType(baseTypeName, nullable, insideGeneric);
  }

  private String mapScalarOrCustomType(
      String graphqlType, boolean nullable, boolean insideGeneric) {
    // Inside generics (e.g., List<>), we must use boxed types, not primitives
    boolean useBoxedType = nullable || insideGeneric;
    return switch (graphqlType) {
      case "String" -> "String";
      case "Int" -> useBoxedType ? "Integer" : "int";
      case "Float" -> useBoxedType ? "Double" : "double";
      case "Boolean" -> useBoxedType ? "Boolean" : "boolean";
      case "ID" -> "String";
      // For custom types (enums, objects, interfaces), use FQN if a package was provided
      default -> grtPackage != null ? grtPackage + "." + graphqlType : graphqlType;
    };
  }
}
