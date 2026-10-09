package viaduct.x.javaapi.codegen;

import java.util.List;

/**
 * Model representing a GraphQL union type for code generation. A union is a marker interface whose
 * member types implement it.
 */
public record UnionModel(
    String packageName, String className, List<String> memberTypes, String description) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<String> getMemberTypes() {
    return memberTypes;
  }

  public String getDescription() {
    return description;
  }

  public boolean getHasDescription() {
    return description != null && !description.isEmpty();
  }

  public boolean getHasMemberTypes() {
    return memberTypes != null && !memberTypes.isEmpty();
  }
}
