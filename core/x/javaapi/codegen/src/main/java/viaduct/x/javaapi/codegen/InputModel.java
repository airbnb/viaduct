package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL input type for code generation. */
public record InputModel(
    String packageName,
    String className,
    List<FieldModel> fields,
    List<FieldModel> reflectedFields,
    String description,
    boolean isOneOf) {

  /** Constructor for models whose generated and reflected fields are identical. */
  public InputModel(
      String packageName,
      String className,
      List<FieldModel> fields,
      String description,
      boolean isOneOf) {
    this(packageName, className, fields, fields, description, isOneOf);
  }

  /**
   * Legacy constructor for non-{@code @oneOf} inputs. Kept so existing call sites and tests
   * continue to compile.
   */
  public InputModel(
      String packageName, String className, List<FieldModel> fields, String description) {
    this(packageName, className, fields, description, false);
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

  public List<FieldModel> getReflectedFields() {
    return reflectedFields;
  }

  public String getDescription() {
    return description;
  }

  public boolean getHasDescription() {
    return description != null && !description.isEmpty();
  }

  public boolean getIsOneOf() {
    return isOneOf;
  }
}
