package viaduct.x.javaapi.codegen;

/**
 * Model representing a GraphQL field resolver for code generation.
 *
 * <p>Each resolver model corresponds to a field annotated with {@code @resolver} directive in the
 * GraphQL schema. The generated class will be an abstract base class that tenant developers extend
 * to implement field resolution logic.
 *
 * <p>Example GraphQL schema:
 *
 * <pre>{@code
 * type User {
 *   profile: Profile @resolver
 * }
 * }</pre>
 *
 * <p>This would generate a resolver model with:
 *
 * <ul>
 *   <li>{@code gqlTypeName = "User"}
 *   <li>{@code gqlFieldName = "profile"}
 *   <li>{@code resolverClassName = "Profile"}
 *   <li>{@code returnType = "Profile"}
 * </ul>
 *
 * @param gqlTypeName the GraphQL type name containing this field (e.g., "User", "Query",
 *     "Mutation"). Used in the {@code @ResolverFor} annotation.
 * @param gqlFieldName the GraphQL field name (e.g., "profile", "orders"). Used in the
 *     {@code @ResolverFor} annotation.
 * @param resolverClassName the generated Java class name for the resolver. Typically, the
 *     capitalized field name (e.g., "Profile" for field "profile").
 * @param returnType the Java return type of the resolver's resolve method (e.g., "Profile",
 *     "List&lt;Order&gt;", "String").
 * @param objectType the fully qualified Java type of the parent object containing this field (e.g.,
 *     "com.example.types.User"). Used as the first type parameter in Context.
 * @param queryType the fully qualified Java type for the Query root (e.g.,
 *     "com.example.types.Query"). Always points to the Query GRT type.
 * @param argumentsType the fully qualified Java type for field arguments. Either
 *     "Arguments.NoArguments" if the field has no arguments, or a generated arguments class like
 *     "com.example.types.Query_User_Arguments".
 * @param selectionsType the fully qualified Java type representing the field's selections. For
 *     composite outputs (objects), this is the GRT type (e.g., "com.example.types.Profile"). For
 *     scalars, this is "CompositeOutput.NotComposite".
 * @param hasArguments whether the GraphQL field has arguments defined.
 * @param isCompositeOutput whether the return type is a composite GraphQL type (object/interface)
 *     rather than a scalar.
 * @param includeBatchResolve whether to generate the {@code batchResolve} method. Set to false for
 *     Mutation fields since batching mutations is not supported.
 */
public record ResolverModel(
    String gqlTypeName,
    String gqlFieldName,
    String resolverClassName,
    String returnType,
    String objectType,
    String queryType,
    String argumentsType,
    String selectionsType,
    boolean hasArguments,
    boolean isCompositeOutput,
    boolean includeBatchResolve) {

  // ===== JavaBean-style getters for StringTemplate =====
  // ST (StringTemplate) requires JavaBean-style getters to access record components.
  // These methods delegate to the record's auto-generated accessor methods.

  /**
   * Returns the GraphQL type name containing this resolver field.
   *
   * @return the GraphQL type name (e.g., "User", "Query")
   */
  public String getGqlTypeName() {
    return gqlTypeName;
  }

  /**
   * Returns the GraphQL field name for this resolver.
   *
   * @return the field name (e.g., "profile", "orders")
   */
  public String getGqlFieldName() {
    return gqlFieldName;
  }

  /**
   * Returns the Java class name for the generated resolver.
   *
   * @return the resolver class name (e.g., "Profile", "Orders")
   */
  public String getResolverClassName() {
    return resolverClassName;
  }

  /**
   * Returns the Java return type for the resolve method.
   *
   * @return the return type (e.g., "Profile", "List&lt;Order&gt;", "String")
   */
  public String getReturnType() {
    return returnType;
  }

  /**
   * Returns the fully qualified Java type of the parent object.
   *
   * @return the object type (e.g., "com.example.types.User")
   */
  public String getObjectType() {
    return objectType;
  }

  /**
   * Returns the fully qualified Java type for the Query root.
   *
   * @return the query type (e.g., "com.example.types.Query")
   */
  public String getQueryType() {
    return queryType;
  }

  /**
   * Returns the fully qualified Java type for field arguments.
   *
   * @return the arguments type (e.g., "Arguments.NoArguments" or a generated arguments class)
   */
  public String getArgumentsType() {
    return argumentsType;
  }

  /**
   * Returns the fully qualified Java type for field selections.
   *
   * @return the selections type (e.g., "com.example.types.Profile" or
   *     "CompositeOutput.NotComposite")
   */
  public String getSelectionsType() {
    return selectionsType;
  }

  /**
   * Returns whether the GraphQL field has arguments.
   *
   * @return true if the field has arguments, false otherwise
   */
  public boolean getHasArguments() {
    return hasArguments;
  }

  /**
   * Returns whether the return type is a composite GraphQL type.
   *
   * @return true if the return type is an object/interface, false if scalar
   */
  public boolean getIsCompositeOutput() {
    return isCompositeOutput;
  }

  /**
   * Returns whether to generate the batchResolve method.
   *
   * @return true to include batchResolve, false to omit (e.g., for Mutation fields)
   */
  public boolean getIncludeBatchResolve() {
    return includeBatchResolve;
  }

  // ===== Pre-formatted type strings for template use =====
  // These methods return fully parameterized generic type strings to avoid angle bracket
  // escaping issues in StringTemplate. Each method builds a complete Java type signature
  // that can be inserted directly into generated code.

  /**
   * Returns the complete generic type signature for {@code FieldResolverBase}.
   *
   * <p>Example output: {@code FieldResolverBase<Profile, com.example.types.User,
   * com.example.types.Query, Arguments.NoArguments, com.example.types.Profile>}
   *
   * @return the FieldResolverBase type with all type parameters
   */
  public String getFieldResolverBaseType() {
    return "FieldResolverBase<"
        + returnType
        + ", "
        + objectType
        + ", "
        + queryType
        + ", "
        + argumentsType
        + ", "
        + selectionsType
        + ">";
  }

  /**
   * Returns the complete generic type signature for {@code FieldResolverBase.Context}.
   *
   * <p>Example output: {@code FieldResolverBase.Context<com.example.types.User,
   * com.example.types.Query, Arguments.NoArguments, com.example.types.Profile>}
   *
   * @return the Context interface type with all type parameters
   */
  public String getContextBaseType() {
    return "FieldResolverBase.Context<"
        + objectType
        + ", "
        + queryType
        + ", "
        + argumentsType
        + ", "
        + selectionsType
        + ">";
  }

  /**
   * Returns the complete generic type signature for {@code FieldExecutionContext}.
   *
   * <p>Example output: {@code FieldExecutionContext<com.example.types.User,
   * com.example.types.Query, Arguments.NoArguments, com.example.types.Profile>}
   *
   * @return the FieldExecutionContext type with all type parameters
   */
  public String getFieldExecutionContextType() {
    return "FieldExecutionContext<"
        + objectType
        + ", "
        + queryType
        + ", "
        + argumentsType
        + ", "
        + selectionsType
        + ">";
  }

  /**
   * Returns the return type for the single-item {@code resolve} method.
   *
   * <p>Example output: {@code CompletableFuture<Profile>}
   *
   * @return the CompletableFuture type wrapping the return type
   */
  public String getResolveFutureType() {
    return "CompletableFuture<" + returnType + ">";
  }

  /**
   * Returns the return type for the {@code batchResolve} method.
   *
   * <p>Example output: {@code CompletableFuture<Map<Context, Profile>>}
   *
   * @return the CompletableFuture type wrapping a Map from Context to return type
   */
  public String getBatchResolveFutureType() {
    return "CompletableFuture<Map<Context, " + returnType + ">>";
  }

  /**
   * Returns the parameter type for the {@code batchResolve} method.
   *
   * <p>Example output: {@code List<Context>}
   *
   * @return the List type containing Context instances
   */
  public String getBatchResolveContextListType() {
    return "List<Context>";
  }
}
