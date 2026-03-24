package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL enum type for code generation. */
public record EnumModel(
    String packageName, String className, List<String> valueNames, String description) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<String> getValueNames() {
    return valueNames;
  }

  public String getDescription() {
    return description;
  }

  public boolean getHasDescription() {
    return description != null && !description.isEmpty();
  }
}
