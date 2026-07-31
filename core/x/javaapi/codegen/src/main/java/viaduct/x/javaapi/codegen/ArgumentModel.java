package viaduct.x.javaapi.codegen;

import java.util.List;

/**
 * Model representing a GraphQL argument type for code generation.
 *
 * @param connectionArgumentsInterface the simple name of the {@code ConnectionArguments}
 *     sub-interface this arguments type should additionally implement (e.g. {@code
 *     "ForwardConnectionArguments"}), or null when the field is not a connection field
 */
public record ArgumentModel(
    String packageName,
    String className,
    List<FieldModel> fields,
    String connectionArgumentsInterface) {

  /** Legacy constructor for non-connection argument types. */
  public ArgumentModel(String packageName, String className, List<FieldModel> fields) {
    this(packageName, className, fields, null);
  }

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<FieldModel> getFields() {
    return fields;
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
