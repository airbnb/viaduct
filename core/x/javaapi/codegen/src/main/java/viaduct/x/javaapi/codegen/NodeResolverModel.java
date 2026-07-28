package viaduct.x.javaapi.codegen;

/**
 * Model for a single node resolver, used to generate Java node resolver base classes.
 *
 * @param tenantPackage tenant package for the generated resolver bases
 *     ({tenantPackage}.resolverbases)
 * @param grtPackage package of the generated GRT types referenced by the resolver bases
 * @param typeName GraphQL type name (e.g., "NodeObj")
 * @param isBatching whether the resolver should generate a batchResolve method
 * @param isSelective whether the resolver is selective
 */
public record NodeResolverModel(
    String tenantPackage,
    String grtPackage,
    String typeName,
    boolean isBatching,
    boolean isSelective) {

  // JavaBean-style getters required by StringTemplate
  public String getTenantPackage() {
    return tenantPackage;
  }

  public String getGrtPackage() {
    return grtPackage;
  }

  public String getTypeName() {
    return typeName;
  }

  public boolean getIsBatching() {
    return isBatching;
  }

  public boolean getIsSelective() {
    return isSelective;
  }

  /** Returns the fully qualified Java class name for the GRT type. */
  public String getGrtType() {
    return grtPackage + "." + typeName;
  }

  public String getBatchingLiteral() {
    return isBatching ? "true" : "false";
  }

  public String getSelectiveLiteral() {
    return isSelective ? "true" : "false";
  }

  /**
   * Returns the simple name of the context interface to implement, switching to {@code
   * SelectiveNodeExecutionContext} when the resolver is selective. Mirrors Kotlin's {@code
   * NodeResolverGenerator.ctxInterface}.
   */
  public String getCtxInterface() {
    return isSelective ? "SelectiveNodeExecutionContext" : "NodeExecutionContext";
  }

  /**
   * Returns the return type for the {@code batchResolve} method, e.g. {@code
   * CompletableFuture<List<FieldValue<pkg.Type>>>}.
   *
   * <p>Matches Kotlin's batchResolve signature ({@code List<FieldValue<T>>}) wrapped in a {@link
   * java.util.concurrent.CompletableFuture} for the Java tenant API.
   */
  public String getBatchResolveFutureType() {
    return "CompletableFuture<List<FieldValue<" + getGrtType() + ">>>";
  }

  public String getBatchInvokerFutureType() {
    return "CompletableFuture<?>";
  }

  public String getBatchInvokerContextListType() {
    return "List<NodeExecutionContext<?>>";
  }
}
