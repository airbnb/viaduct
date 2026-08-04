package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL object type for code generation. */
public record ObjectModel(
    String packageName,
    String className,
    List<String> implementedInterfaces,
    List<FieldModel> fields,
    List<FieldModel> reflectedFields,
    String description,
    boolean isRootType,
    boolean isNodeType,
    boolean isConnection,
    boolean isEdge,
    String edgeTypeName,
    String nodeTypeName) {

  /** Constructor for models whose generated and reflected fields are identical. */
  public ObjectModel(
      String packageName,
      String className,
      List<String> implementedInterfaces,
      List<FieldModel> fields,
      String description,
      boolean isRootType,
      boolean isNodeType,
      boolean isConnection,
      boolean isEdge,
      String edgeTypeName,
      String nodeTypeName) {
    this(
        packageName,
        className,
        implementedInterfaces,
        fields,
        fields,
        description,
        isRootType,
        isNodeType,
        isConnection,
        isEdge,
        edgeTypeName,
        nodeTypeName);
  }

  /**
   * Legacy constructor for object types that are neither connections nor edges. Kept so existing
   * call sites and tests continue to compile.
   */
  public ObjectModel(
      String packageName,
      String className,
      List<String> implementedInterfaces,
      List<FieldModel> fields,
      String description,
      boolean isRootType,
      boolean isNodeType) {
    this(
        packageName,
        className,
        implementedInterfaces,
        fields,
        description,
        isRootType,
        isNodeType,
        false,
        false,
        null,
        null);
  }

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

  public List<FieldModel> getReflectedFields() {
    return reflectedFields;
  }

  public String getDescription() {
    return description;
  }

  public boolean getIsNodeType() {
    return isNodeType;
  }

  public boolean getIsConnection() {
    return isConnection;
  }

  public boolean getIsEdge() {
    return isEdge;
  }

  public String getEdgeTypeName() {
    return edgeTypeName;
  }

  public String getNodeTypeName() {
    return nodeTypeName;
  }

  /** Pre-formatted {@code ConnectionBuilder<Conn, Edge, Node>} generated-builder supertype. */
  public String getConnectionBuilderSupertype() {
    return "ConnectionBuilder<" + className + ", " + edgeTypeName + ", " + nodeTypeName + ">";
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
   * <p>Since all generated object classes now extend {@code ObjectBase} (which implements {@code
   * GraphQLObject}), an implements clause is only needed when there are additional interfaces (root
   * type marker or user-defined interfaces) or a connection/edge marker interface.
   */
  public boolean getHasImplementsClause() {
    return isRootType
        || isConnection
        || isEdge
        || (implementedInterfaces != null && !implementedInterfaces.isEmpty());
  }

  /**
   * Returns the implements clause for the class declaration (without GraphQLObject, which is
   * inherited from ObjectBase). For root types, uses the appropriate marker interface. Connection
   * and edge types add their {@code Connection<Edge, Node>} / {@code Edge<Node>} marker. All other
   * user-defined interfaces are appended.
   */
  public String getImplementsClause() {
    List<String> clauses = new java.util.ArrayList<>();

    if (isRootType) {
      // Root types use their specific marker interface (which extends GraphQLObject)
      clauses.add("viaduct.java.api.types." + className);
    }
    if (isConnection && edgeTypeName != null && nodeTypeName != null) {
      clauses.add("viaduct.java.api.types.Connection<" + edgeTypeName + ", " + nodeTypeName + ">");
    }
    if (isEdge && nodeTypeName != null) {
      clauses.add("viaduct.java.api.types.Edge<" + nodeTypeName + ">");
    }
    if (implementedInterfaces != null) {
      clauses.addAll(implementedInterfaces);
    }

    return String.join(", ", clauses);
  }
}
