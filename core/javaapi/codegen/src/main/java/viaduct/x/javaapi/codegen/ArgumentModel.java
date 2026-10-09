package viaduct.x.javaapi.codegen;

import java.util.List;

/**
 * Model representing a GraphQL argument type for code generation.
 *
 * @param containingTypeName GraphQL name of the type containing the field
 * @param fieldName GraphQL name of the field that declares these arguments
 * @param fields schema-declared argument fields
 * @param synthesizedConnectionFields null-returning compatibility getters required by the selected
 *     {@code ConnectionArguments} interface but absent from the schema
 * @param connectionArgumentsInterface the simple name of the {@code ConnectionArguments}
 *     sub-interface this arguments type should additionally implement (e.g. {@code
 *     "ForwardConnectionArguments"}), or null when the field is not a connection field
 */
public record ArgumentModel(
    String packageName,
    String className,
    String containingTypeName,
    String fieldName,
    List<FieldModel> fields,
    List<FieldModel> synthesizedConnectionFields,
    String connectionArgumentsInterface) {

  /** Convenience constructor for non-connection argument types. */
  public ArgumentModel(
      String packageName,
      String className,
      String containingTypeName,
      String fieldName,
      List<FieldModel> fields) {
    this(packageName, className, containingTypeName, fieldName, fields, List.of(), null);
  }

  public ArgumentModel(
      String packageName,
      String className,
      String containingTypeName,
      String fieldName,
      List<FieldModel> fields,
      String connectionArgumentsInterface) {
    this(
        packageName,
        className,
        containingTypeName,
        fieldName,
        fields,
        List.of(),
        connectionArgumentsInterface);
  }

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public String getContainingTypeName() {
    return containingTypeName;
  }

  public String getFieldName() {
    return fieldName;
  }

  public List<FieldModel> getFields() {
    return fields;
  }

  public List<FieldModel> getReflectedFields() {
    return fields;
  }

  public List<FieldModel> getSynthesizedConnectionFields() {
    return synthesizedConnectionFields;
  }

  /**
   * Returns true when this arguments type implements a {@code ConnectionArguments} sub-interface.
   */
  public boolean getIsConnectionArguments() {
    return connectionArgumentsInterface != null;
  }

  /**
   * Returns the {@code implements} clause for connection arguments (a fully qualified {@code
   * ConnectionArguments} sub-interface), or the empty string when not a connection field.
   */
  public String getConnectionArgumentsClause() {
    return connectionArgumentsInterface == null
        ? ""
        : "viaduct.java.api.types." + connectionArgumentsInterface;
  }
}
