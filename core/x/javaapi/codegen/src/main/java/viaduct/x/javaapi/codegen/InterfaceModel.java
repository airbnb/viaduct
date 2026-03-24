package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL interface type for code generation. */
public record InterfaceModel(
    String packageName,
    String className,
    List<String> extendedInterfaces,
    List<FieldModel> fields,
    String description) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<String> getExtendedInterfaces() {
    return extendedInterfaces;
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

  public boolean getHasExtendedInterfaces() {
    return extendedInterfaces != null && !extendedInterfaces.isEmpty();
  }

  /**
   * Returns the extends clause for the interface declaration. Always includes GraphQLInterface,
   * plus any extended interfaces.
   */
  public String getExtendsClause() {
    StringBuilder sb = new StringBuilder("GraphQLInterface");
    if (extendedInterfaces != null) {
      for (String iface : extendedInterfaces) {
        sb.append(", ").append(iface);
      }
    }
    return sb.toString();
  }
}
