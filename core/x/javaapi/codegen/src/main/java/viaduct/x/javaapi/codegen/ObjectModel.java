package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL object type for code generation. */
public record ObjectModel(
    String packageName,
    String className,
    List<String> implementedInterfaces,
    List<FieldModel> fields,
    String description,
    boolean isRootType) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<String> getImplementedInterfaces() {
    return implementedInterfaces;
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

  public boolean getHasInterfaces() {
    return implementedInterfaces != null && !implementedInterfaces.isEmpty();
  }

  /**
   * Returns the implements clause for the class declaration. For root types, uses the appropriate
   * marker interface. For other types, uses GraphQLObject plus any implemented interfaces.
   */
  public String getImplementsClause() {
    // Root types use their specific marker interface (which extends GraphQLObject)
    String baseInterface = isRootType ? "viaduct.java.api.types." + className : "GraphQLObject";

    StringBuilder sb = new StringBuilder(baseInterface);
    if (implementedInterfaces != null) {
      for (String iface : implementedInterfaces) {
        sb.append(", ").append(iface);
      }
    }
    return sb.toString();
  }
}
