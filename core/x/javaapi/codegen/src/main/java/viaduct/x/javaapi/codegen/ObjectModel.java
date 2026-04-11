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
   * Returns true if the class declaration needs an implements clause.
   *
   * <p>Since all generated object classes now extend {@code JavaObjectBase} (which implements
   * {@code GraphQLObject}), an implements clause is only needed when there are additional
   * interfaces (root type marker or user-defined interfaces).
   */
  public boolean getHasImplementsClause() {
    return isRootType || (implementedInterfaces != null && !implementedInterfaces.isEmpty());
  }

  /**
   * Returns the implements clause for the class declaration (without GraphQLObject, which is
   * inherited from JavaObjectBase). For root types, uses the appropriate marker interface. For
   * other types, uses any implemented interfaces only.
   */
  public String getImplementsClause() {
    if (isRootType) {
      // Root types use their specific marker interface (which extends GraphQLObject)
      StringBuilder sb = new StringBuilder("viaduct.java.api.types." + className);
      if (implementedInterfaces != null) {
        for (String iface : implementedInterfaces) {
          sb.append(", ").append(iface);
        }
      }
      return sb.toString();
    }

    // Non-root types: only list user-defined interfaces (GraphQLObject is inherited from
    // JavaObjectBase)
    if (implementedInterfaces == null || implementedInterfaces.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String iface : implementedInterfaces) {
      if (!first) sb.append(", ");
      sb.append(iface);
      first = false;
    }
    return sb.toString();
  }
}
