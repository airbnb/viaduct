package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL input type for code generation. */
public record InputModel(
    String packageName, String className, List<FieldModel> fields, String description) {

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

  public String getDescription() {
    return description;
  }

  public boolean getHasDescription() {
    return description != null && !description.isEmpty();
  }
}
