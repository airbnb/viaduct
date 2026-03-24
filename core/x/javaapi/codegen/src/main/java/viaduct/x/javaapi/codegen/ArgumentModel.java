package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL argument type for code generation. */
public record ArgumentModel(String packageName, String className, List<FieldModel> fields) {

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
}
