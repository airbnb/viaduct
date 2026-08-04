package viaduct.x.javaapi.codegen;

import static viaduct.tenant.codegen.bytecode.config.ViaductSchemaExtensionsKt.getHasReflectedType;
import static viaduct.tenant.codegen.bytecode.config.ViaductSchemaExtensionsKt.isRootObjectFieldEligible;
import static viaduct.tenant.codegen.bytecode.config.ViaductSchemaExtensionsKt.mutationNamespaceTypeNames;
import static viaduct.tenant.codegen.bytecode.config.ViaductSchemaExtensionsKt.pathFromQueryRoot;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import viaduct.codegen.ConnectionArgScalarKind;
import viaduct.codegen.ConnectionArgumentsDirection;
import viaduct.codegen.SchemaAnalysis;
import viaduct.graphql.schema.ViaductReverseSchema;
import viaduct.graphql.schema.ViaductSchema;
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory;

/**
 * Parses GraphQL schema files and extracts type definitions using ViaductSchema types. This follows
 * the same approach as the Kotlin codegen, using ViaductSchema as the abstraction layer instead of
 * graphql-java types directly.
 */
public class GraphQLSchemaParser {

  /**
   * Parses a GraphQL schema from a Reader and returns the ViaductSchema.
   *
   * @param reader the reader to parse from
   * @return the ViaductSchema
   * @throws IOException if there's an error reading the content
   */
  public ViaductSchema parse(Reader reader) throws IOException {
    String sdl = readAll(reader);
    return ViaductSchemaFactory.fromTypeDefinitionRegistry(sdl);
  }

  /**
   * Parses a GraphQL schema file and returns the ViaductSchema.
   *
   * @param schemaFile the schema file to parse
   * @return the ViaductSchema
   */
  public ViaductSchema parse(File schemaFile) {
    return ViaductSchemaFactory.fromTypeDefinitionRegistry(List.of(schemaFile));
  }

  /**
   * Parses multiple GraphQL schema files and merges them into a ViaductSchema.
   *
   * @param schemaFiles the schema files to parse
   * @return the merged ViaductSchema
   */
  public ViaductSchema parse(List<File> schemaFiles) {
    return ViaductSchemaFactory.fromTypeDefinitionRegistry(schemaFiles);
  }

  /**
   * Extracts enum models from a ViaductSchema. Extensions are already merged in ViaductSchema.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated enums
   * @return the list of enum models
   */
  public List<EnumModel> extractEnums(ViaductSchema schema, String packageName) {
    List<EnumModel> enums = new ArrayList<>();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (typeDef instanceof ViaductSchema.Enum enumDef) {
        String name = enumDef.getName();

        // Collect all enum values (extensions are already merged in ViaductSchema)
        List<String> valueNames =
            enumDef.getValues().stream()
                .map(ViaductSchema.EnumValue::getName)
                .collect(Collectors.toList());

        String description = getDescription(enumDef);

        enums.add(new EnumModel(packageName, name, valueNames, description));
      }
    }

    return enums;
  }

  /**
   * Extracts object models from a ViaductSchema. Excludes root types (Query, Mutation,
   * Subscription).
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated objects
   * @return the list of object models
   */
  public List<ObjectModel> extractObjects(ViaductSchema schema, String packageName) {
    return extractObjects(schema, packageName, false);
  }

  /**
   * Extracts object models from a ViaductSchema with optional root type inclusion.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated objects
   * @param includeRootTypes if true, includes Query, Mutation, Subscription types
   * @return the list of object models
   */
  public List<ObjectModel> extractObjects(
      ViaductSchema schema, String packageName, boolean includeRootTypes) {
    List<ObjectModel> objects = new ArrayList<>();
    TypeMapper typeMapper = new TypeMapper();
    ViaductReverseSchema reverseSchema = ViaductReverseSchema.Companion.from(schema);

    // Root types to exclude from generation (unless explicitly included)
    Set<String> rootTypes = Set.of("Query", "Mutation", "Subscription");

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (typeDef instanceof ViaductSchema.Object objectDef) {
        String name = objectDef.getName();

        // Skip root types unless includeRootTypes is true
        if (!includeRootTypes && rootTypes.contains(name)) {
          continue;
        }

        // Collect implemented interfaces (already includes extensions)
        List<String> interfaces =
            objectDef.getSupers().stream()
                .map(ViaductSchema.Interface::getName)
                .collect(Collectors.toCollection(ArrayList::new));

        // Add union types this object belongs to (union members implement the union interface)
        for (ViaductSchema.Union union : objectDef.getUnions()) {
          interfaces.add(union.getName());
        }

        // BackingData fields are reflected for schema parity, but remain opaque at the Java GRT
        // level and therefore do not get getters or builder methods.
        List<FieldModel> fields = new ArrayList<>();
        List<FieldModel> reflectedFields = new ArrayList<>();
        List<String> rootPath =
            pathFromQueryRoot(objectDef, reverseSchema, schema.getQueryTypeDef());
        for (ViaductSchema.Field field : objectDef.getFields()) {
          FieldModel fieldModel = createFieldModel(field, typeMapper, objectDef, rootPath);
          reflectedFields.add(fieldModel);
          if (!isBackingDataField(field)) {
            fields.add(fieldModel);
          }
        }

        boolean isNodeType = isNodeType(objectDef);

        // Connection/edge metadata (@connection / @edge). Reuses the shared, language-neutral
        // SchemaAnalysis helpers so the Java parser stays in lockstep with the Kotlin codegen.
        boolean isConnection = SchemaAnalysis.INSTANCE.hasConnectionDirective(objectDef);
        boolean isEdge = SchemaAnalysis.INSTANCE.hasEdgeDirective(objectDef);
        String edgeTypeName = null;
        String nodeTypeName = null;
        if (isEdge) {
          nodeTypeName = SchemaAnalysis.INSTANCE.edgeNodeTypeName(objectDef);
        }
        if (isConnection) {
          edgeTypeName = SchemaAnalysis.INSTANCE.connectionEdgeTypeName(objectDef);
          // The connection's node type is the node type of its edge.
          if (edgeTypeName != null) {
            ViaductSchema.TypeDef edgeDef = schema.getTypes().get(edgeTypeName);
            if (edgeDef instanceof ViaductSchema.Object edgeObj) {
              nodeTypeName = SchemaAnalysis.INSTANCE.edgeNodeTypeName(edgeObj);
            }
          }
        }

        objects.add(
            new ObjectModel(
                packageName,
                name,
                interfaces,
                fields,
                reflectedFields,
                getDescription(objectDef),
                rootTypes.contains(name),
                isNodeType,
                isConnection,
                isEdge,
                edgeTypeName,
                nodeTypeName));
      }
    }

    return objects;
  }

  /**
   * Extracts input models from a ViaductSchema.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated inputs
   * @return the list of input models
   */
  public List<InputModel> extractInputs(ViaductSchema schema, String packageName) {
    List<InputModel> inputs = new ArrayList<>();
    TypeMapper typeMapper = new TypeMapper();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (typeDef instanceof ViaductSchema.Input inputDef) {
        String name = inputDef.getName();

        // Collect generated fields separately from the complete reflection field set.
        List<FieldModel> fields = new ArrayList<>();
        List<FieldModel> reflectedFields = new ArrayList<>();
        for (ViaductSchema.Field field : inputDef.getFields()) {
          FieldModel fieldModel = createFieldModel(field, typeMapper, inputDef, null);
          reflectedFields.add(fieldModel);
          if (!isBackingDataField(field)) {
            fields.add(fieldModel);
          }
        }

        boolean isOneOf = SchemaAnalysis.INSTANCE.hasOneOfDirective(inputDef);
        inputs.add(
            new InputModel(
                packageName, name, fields, reflectedFields, getDescription(inputDef), isOneOf));
      }
    }

    return inputs;
  }

  /**
   * Extracts interface models from a ViaductSchema.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated interfaces
   * @return the list of interface models
   */
  public List<InterfaceModel> extractInterfaces(ViaductSchema schema, String packageName) {
    List<InterfaceModel> interfaces = new ArrayList<>();
    TypeMapper typeMapper = new TypeMapper();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (typeDef instanceof ViaductSchema.Interface interfaceDef) {
        String name = interfaceDef.getName();

        // Collect extended interfaces (interfaces can extend other interfaces)
        List<String> extendedInterfaces =
            interfaceDef.getSupers().stream()
                .map(ViaductSchema.Interface::getName)
                .collect(Collectors.toCollection(ArrayList::new));

        // Detect if this interface is or extends Node (recursive)
        boolean isNodeInterface = isNodeType(interfaceDef);

        // Collect generated fields separately from the complete reflection field set.
        List<FieldModel> fields = new ArrayList<>();
        List<FieldModel> reflectedFields = new ArrayList<>();
        for (ViaductSchema.Field field : interfaceDef.getFields()) {
          FieldModel fieldModel = createFieldModel(field, typeMapper, interfaceDef, null);
          reflectedFields.add(fieldModel);
          if (!isBackingDataField(field)) {
            fields.add(fieldModel);
          }
        }

        interfaces.add(
            new InterfaceModel(
                packageName,
                name,
                extendedInterfaces,
                fields,
                reflectedFields,
                getDescription(interfaceDef),
                isNodeInterface));
      }
    }

    return interfaces;
  }

  /**
   * Extracts union models from a ViaductSchema.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated unions
   * @return the list of union models
   */
  public List<UnionModel> extractUnions(ViaductSchema schema, String packageName) {
    List<UnionModel> unions = new ArrayList<>();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (typeDef instanceof ViaductSchema.Union unionDef) {
        String name = unionDef.getName();

        // Collect member types (already includes extensions)
        List<String> memberTypes =
            unionDef.getPossibleObjectTypes().stream()
                .map(ViaductSchema.Object::getName)
                .collect(Collectors.toCollection(ArrayList::new));

        unions.add(new UnionModel(packageName, name, memberTypes, getDescription(unionDef)));
      }
    }

    return unions;
  }

  /**
   * Extracts argument models from a ViaductSchema for resolver fields that have arguments.
   *
   * @param schema the ViaductSchema
   * @param packageName the package name for generated argument types
   * @param mutationTypeName the name of the mutation type (or null if none)
   * @return the list of argument models
   */
  public List<ArgumentModel> extractArguments(
      ViaductSchema schema, String packageName, String mutationTypeName) {
    TypeMapper typeMapper = new TypeMapper();
    List<ArgumentModel> arguments = new ArrayList<>();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (!(typeDef instanceof ViaductSchema.Object objectType)) continue;
      for (ViaductSchema.Field field : objectType.getFields()) {
        if (!field.getHasArgs()) continue;

        String className =
            SchemaAnalysis.INSTANCE.argumentsTypeName(objectType.getName(), field.getName());

        // Connection fields: their arguments type additionally implements a ConnectionArguments
        // sub-interface, and pagination args must be boxed and nullable to match its getters.
        String connectionArgsInterface = connectionArgumentsInterface(field);
        boolean isConnectionArgs = connectionArgsInterface != null;
        Set<String> requiredConnectionArgNames =
            SchemaAnalysis.INSTANCE.connectionArgumentRequiredNames(
                SchemaAnalysis.INSTANCE.connectionArgumentsDirection(field));

        List<FieldModel> fields = new ArrayList<>();
        for (ViaductSchema.FieldArg arg : field.getArgs()) {
          ViaductSchema.TypeDef argBaseTypeDef = arg.getType().getBaseTypeDef();
          boolean argCompositeType =
              (argBaseTypeDef instanceof ViaductSchema.Object)
                  || (argBaseTypeDef instanceof ViaductSchema.Input);
          boolean argList = arg.getType().isList();
          boolean argEnumType = argBaseTypeDef instanceof ViaductSchema.Enum;
          boolean argAbstractType =
              (argBaseTypeDef instanceof ViaductSchema.Interface)
                  || (argBaseTypeDef instanceof ViaductSchema.Union);
          String argBaseTypeName =
              (argCompositeType || argEnumType || argAbstractType)
                  ? argBaseTypeDef.getName()
                  : null;

          String argIdOfTypeName = SchemaAnalysis.INSTANCE.idOfTypeName(arg);
          boolean argGlobalIDType = false;
          boolean paginationArg = requiredConnectionArgNames.contains(arg.getName());
          boolean argNullable = arg.getType().isNullable() || paginationArg;
          String argJavaType =
              paginationArg
                  ? typeMapper.toBoxedJavaType(arg.getType())
                  : typeMapper.toJavaType(arg.getType());
          if (argIdOfTypeName != null) {
            argGlobalIDType = true;
            argBaseTypeName = argIdOfTypeName;
            argJavaType =
                argList
                    ? "List<GlobalID<" + argIdOfTypeName + ">>"
                    : "GlobalID<" + argIdOfTypeName + ">";
          }

          fields.add(
              new FieldModel(
                  arg.getName(),
                  argJavaType,
                  argNullable,
                  argCompositeType,
                  argList,
                  argEnumType,
                  argAbstractType,
                  argGlobalIDType,
                  argBaseTypeName,
                  getHasReflectedType(argBaseTypeDef) ? argBaseTypeDef.getName() : null,
                  false,
                  null,
                  null));
        }
        List<FieldModel> synthesizedConnectionFields =
            isConnectionArgs ? synthesizedConnectionArgGetters(field) : List.of();
        arguments.add(
            new ArgumentModel(
                packageName,
                className,
                fields,
                synthesizedConnectionFields,
                connectionArgsInterface));
      }
    }

    return arguments;
  }

  /**
   * Extracts resolver models from a ViaductSchema by finding fields with @resolver directive.
   *
   * <p>Groups resolvers by their containing type name. Each type with resolver fields will have an
   * entry in the returned map.
   *
   * @param schema the ViaductSchema
   * @param grtPackage the package name for GRT types
   * @param mutationTypeName the name of the mutation type (or null if none)
   * @return a map from type name to list of resolver models for that type
   */
  public Map<String, List<ResolverModel>> extractResolvers(
      ViaductSchema schema, String grtPackage, String mutationTypeName) {
    TypeMapper typeMapper = new TypeMapper(grtPackage);
    Map<String, List<ResolverModel>> result = new java.util.LinkedHashMap<>();

    // Names of @namespaceType objects reachable from the mutation root. Reuses the same helper as
    // the Kotlin codegen so both paths reject selective/batching resolvers on namespaced mutations.
    Set<String> mutationNamespaceNames = mutationNamespaceTypeNames(schema);

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (!(typeDef instanceof ViaductSchema.Object objectDef)) {
        continue;
      }

      String typeName = objectDef.getName();
      List<ResolverModel> resolvers = new java.util.ArrayList<>();

      // Look for fields with @resolver directive in extensions
      for (ViaductSchema.Extension<?, ?> extension : objectDef.getExtensions()) {
        for (Object member : extension.getMembers()) {
          if (member instanceof ViaductSchema.Field field) {
            if (field.hasAppliedDirective("resolver")) {
              ResolverModel model =
                  createResolverModel(
                      field,
                      typeName,
                      grtPackage,
                      typeMapper,
                      mutationTypeName,
                      mutationNamespaceNames);
              resolvers.add(model);
            }
          }
        }
      }

      if (!resolvers.isEmpty()) {
        result.put(typeName, resolvers);
      }
    }

    return result;
  }

  /**
   * Creates a ResolverModel from a ViaductSchema.Field.
   *
   * @param field the field with @resolver directive
   * @param typeName the containing type name
   * @param grtPackage the GRT package name
   * @param typeMapper the type mapper
   * @param mutationTypeName the mutation type name
   * @param mutationNamespaceNames names of @namespaceType objects reachable from the mutation root
   * @return the resolver model
   */
  private ResolverModel createResolverModel(
      ViaductSchema.Field field,
      String typeName,
      String grtPackage,
      TypeMapper typeMapper,
      String mutationTypeName,
      Set<String> mutationNamespaceNames) {
    String fieldName = field.getName();
    String resolverClassName = SchemaAnalysis.INSTANCE.resolverClassName(fieldName);

    // Determine return type (always boxed since it appears inside CompletableFuture<> and
    // FieldResolverBase<> generic parameters)
    String returnType = typeMapper.toBoxedJavaType(field.getType());

    // Object type (the type containing this field)
    String objectType = grtPackage + "." + typeName;

    // Query type is always the Query GRT
    String queryType = grtPackage + ".Query";

    // Mutation type is the Mutation GRT if the schema has a Mutation type, otherwise null
    String mutationType = mutationTypeName != null ? grtPackage + "." + mutationTypeName : null;

    // Arguments type - use Arguments.None if field has no arguments
    boolean hasArguments = field.getHasArgs();
    String argumentsType =
        hasArguments
            ? grtPackage + "." + SchemaAnalysis.INSTANCE.argumentsTypeName(typeName, fieldName)
            : "Arguments.None";

    // Selections type - use CompositeOutput.None if output is not a composite type
    boolean isCompositeOutput = field.getType().getBaseTypeDef().isComposite();
    String selectionsType =
        isCompositeOutput
            ? grtPackage + "." + field.getType().getBaseTypeDef().getName()
            : "CompositeOutput.None";
    boolean isSelective =
        isSelectiveResolver(field, typeName, mutationTypeName, mutationNamespaceNames);
    boolean isBatching =
        isBatchingResolver(field, typeName, mutationTypeName, mutationNamespaceNames);
    // Fields returning a @connection type get the connection-specific resolver base + context, so
    // the generated developer surface matches the hand-written ConnectionResolverBase API. But a
    // @connection field that declares no pagination arguments (first/after/last/before) has no
    // ConnectionArguments to expose, so it gets the ordinary FieldResolverBase instead — matching
    // connectionArgumentsInterface, which returns null for that shape. Keying off the pagination
    // direction (rather than the bare directive) keeps the two decisions in lockstep.
    boolean isConnection =
        SchemaAnalysis.INSTANCE.connectionArgumentsDirection(field)
            != ConnectionArgumentsDirection.NONE;

    return new ResolverModel(
        typeName,
        fieldName,
        resolverClassName,
        returnType,
        objectType,
        queryType,
        mutationType,
        argumentsType,
        selectionsType,
        hasArguments,
        isCompositeOutput,
        isSelective,
        isBatching,
        isConnection);
  }

  private boolean isBatchingResolver(
      ViaductSchema.Def def,
      String typeName,
      String mutationTypeName,
      Set<String> mutationNamespaceNames) {
    boolean isBatching = SchemaAnalysis.INSTANCE.isBatchingResolver(def);
    if (isMutationSideType(typeName, mutationTypeName, mutationNamespaceNames)) {
      if (isBatching) {
        throw new IllegalArgumentException(
            "@resolver(isBatching: true) is not supported on mutation field "
                + typeName
                + "."
                + def.getName());
      }
      return false;
    }
    return isBatching;
  }

  private boolean isSelectiveResolver(
      ViaductSchema.Def def,
      String typeName,
      String mutationTypeName,
      Set<String> mutationNamespaceNames) {
    boolean isSelective = SchemaAnalysis.INSTANCE.isSelectiveResolver(def);
    if (isMutationSideType(typeName, mutationTypeName, mutationNamespaceNames)) {
      if (isSelective) {
        throw new IllegalArgumentException(
            "@resolver(isSelective: true) is not supported on mutation field "
                + typeName
                + "."
                + def.getName());
      }
      return false;
    }
    return isSelective;
  }

  /**
   * A field executes as a mutation if it lives on the root mutation type or on a @namespaceType
   * object reachable from it. Mirrors the Kotlin codegen's {@code isMutationSideType}.
   */
  private boolean isMutationSideType(
      String typeName, String mutationTypeName, Set<String> mutationNamespaceNames) {
    return typeName.equals(mutationTypeName) || mutationNamespaceNames.contains(typeName);
  }

  /**
   * Extracts node resolver models from a ViaductSchema by finding Object types that implement the
   * Node interface and have the {@code @resolver} directive applied directly to the type.
   *
   * <p>When {@code tenantModule} is non-null, only types whose source location belongs to that
   * tenant module are included — mirroring Kotlin's {@code NodeResolverGenerator.isTenantOwnedNode}
   * filter. When null, all matching types are returned (used in feature-app tests).
   *
   * @param schema the ViaductSchema
   * @param tenantPackage the tenant package for generated resolver bases
   * @param grtPackage the package name for the generated GRT types
   * @param tenantModule optional tenant module path (e.g., "tenant/runtime/execution/foo"); when
   *     provided, only nodes from that module are included
   * @return the list of node resolver models
   */
  public List<NodeResolverModel> extractNodeResolvers(
      ViaductSchema schema, String tenantPackage, String grtPackage, String tenantModule) {
    List<NodeResolverModel> nodeResolvers = new ArrayList<>();
    Predicate<ViaductSchema.TypeDef> ownershipFilter = ownershipFilter(tenantModule);

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (!(typeDef instanceof ViaductSchema.Object objectDef)) continue;
      if (!objectDef.hasAppliedDirective("resolver")) continue;

      if (!isNodeType(objectDef)) continue;

      if (!ownershipFilter.test(objectDef)) continue;

      // Nodes are never a mutation type or namespace, so the mutation namespace set is empty.
      boolean isBatching =
          isBatchingResolver(objectDef, objectDef.getName(), null, Collections.emptySet());
      boolean isSelective =
          isSelectiveResolver(objectDef, objectDef.getName(), null, Collections.emptySet());

      nodeResolvers.add(
          new NodeResolverModel(
              tenantPackage, grtPackage, objectDef.getName(), isBatching, isSelective));
    }

    return nodeResolvers;
  }

  private static Predicate<ViaductSchema.TypeDef> ownershipFilter(String tenantModule) {
    if (tenantModule == null) return def -> true;
    return def -> {
      var loc = def.getSourceLocation();
      if (loc == null) return false;
      String module = SchemaAnalysis.INSTANCE.buildTimeTenantModule(loc.getSourceName());
      return tenantModule.equals(module);
    };
  }

  // ===== Helper methods =====

  /** Reads all content from a Reader into a String. */
  private String readAll(Reader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br = new BufferedReader(reader)) {
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line).append("\n");
      }
    }
    return sb.toString();
  }

  /**
   * Returns true if the given type definition is or transitively implements the Node interface.
   * Delegates to the shared language-neutral {@link SchemaAnalysis}.
   */
  private static boolean isNodeType(ViaductSchema.TypeDef typeDef) {
    return SchemaAnalysis.INSTANCE.isNode(typeDef);
  }

  /**
   * Returns the simple name of the {@code ConnectionArguments} sub-interface a resolver field's
   * arguments type should implement (e.g. {@code "ForwardConnectionArguments"}), or null when the
   * field does not return a {@code @connection} type or declares no pagination arguments. Delegates
   * the direction decision to the shared {@link SchemaAnalysis} so the Java and Kotlin codegens
   * agree.
   */
  private static String connectionArgumentsInterface(ViaductSchema.Field field) {
    return switch (SchemaAnalysis.INSTANCE.connectionArgumentsDirection(field)) {
      case NONE -> null;
      case FORWARD -> "ForwardConnectionArguments";
      case BACKWARD -> "BackwardConnectionArguments";
      case MULTIDIRECTIONAL -> "MultidirectionalConnectionArguments";
    };
  }

  /**
   * Returns synthetic null-returning getters for the pagination arguments the field's {@code
   * ConnectionArguments} sub-interface requires but the schema does not declare (e.g. {@code after}
   * on a {@code first}-only field). Without these, the generated class would not satisfy the
   * interface and fail to compile. See {@link SchemaAnalysis#synthesizedConnectionArgumentNames}.
   */
  private static List<FieldModel> synthesizedConnectionArgGetters(ViaductSchema.Field field) {
    List<FieldModel> fields = new ArrayList<>();
    for (String argName : SchemaAnalysis.INSTANCE.synthesizedConnectionArgumentNames(field)) {
      ConnectionArgScalarKind kind = SchemaAnalysis.INSTANCE.connectionArgumentScalarKind(argName);
      if (kind == null) {
        throw new IllegalStateException("Not a pagination argument: " + argName);
      }
      String javaType =
          switch (kind) {
            case INT -> "Integer";
            case STRING -> "String";
          };
      fields.add(FieldModel.simple(argName, javaType, true));
    }
    return fields;
  }

  /**
   * Returns true if the field's base type is the BackingData scalar. BackingData fields are
   * excluded from Java GRT codegen — they are opaque containers whose runtime type is specified
   * per-field via the @backingData directive. (This exclusion is a Java-side policy; the shared
   * analysis only answers whether the field is BackingData-typed.)
   */
  private boolean isBackingDataField(ViaductSchema.Field field) {
    return SchemaAnalysis.INSTANCE.isBackingDataField(field);
  }

  /** Creates a FieldModel from a ViaductSchema.Field. */
  private FieldModel createFieldModel(
      ViaductSchema.Field field,
      TypeMapper typeMapper,
      ViaductSchema.TypeDef containerType,
      List<String> pathToContainer) {
    String javaType = typeMapper.toJavaType(field.getType());
    boolean nullable = field.getType().isNullable();
    ViaductSchema.TypeDef baseTypeDef = field.getType().getBaseTypeDef();
    // Only concrete object types and input types can be instantiated via constructor reference.
    // Interface and union types become Java interfaces, so they use fetchAbstractObject instead.
    boolean compositeType =
        (baseTypeDef instanceof ViaductSchema.Object)
            || (baseTypeDef instanceof ViaductSchema.Input);
    boolean list = field.getType().isList();
    boolean enumType = baseTypeDef instanceof ViaductSchema.Enum;
    // Interface and union types require runtime type resolution (like Kotlin's wrapObject).
    boolean abstractType =
        (baseTypeDef instanceof ViaductSchema.Interface)
            || (baseTypeDef instanceof ViaductSchema.Union);
    String baseTypeName =
        (compositeType || enumType || abstractType) ? baseTypeDef.getName() : null;

    // Detect @idOf directive → field should be typed as GlobalID<T>
    boolean globalIDType = false;
    String idOfTypeName = SchemaAnalysis.INSTANCE.idOfTypeName(field);
    if (idOfTypeName != null) {
      globalIDType = true;
      baseTypeName = idOfTypeName;
      javaType = list ? "List<GlobalID<" + idOfTypeName + ">>" : "GlobalID<" + idOfTypeName + ">";
    }

    // Detect Node.id → GlobalID<ContainerType> (mirrors Kotlin's isGlobalID check)
    if (idOfTypeName == null && field.getName().equals("id") && isNodeType(containerType)) {
      globalIDType = true;
      baseTypeName = containerType.getName();
      boolean isInterface = containerType instanceof ViaductSchema.Interface;
      javaType =
          isInterface
              ? "GlobalID<? extends " + containerType.getName() + ">"
              : "GlobalID<" + containerType.getName() + ">";
    }

    String reflectedTypeName = getHasReflectedType(baseTypeDef) ? baseTypeDef.getName() : null;
    boolean rootObjectField = isRootObjectFieldEligible(field, pathToContainer);
    String argumentsTypeName = null;
    List<String> pathFromQueryRoot = null;
    if (rootObjectField) {
      argumentsTypeName =
          field.getHasArgs()
              ? SchemaAnalysis.INSTANCE.argumentsTypeName(containerType.getName(), field.getName())
              : "Arguments.None";
      pathFromQueryRoot = new ArrayList<>(pathToContainer);
      pathFromQueryRoot.add(field.getName());
    }

    return new FieldModel(
        field.getName(),
        javaType,
        nullable,
        compositeType,
        list,
        enumType,
        abstractType,
        globalIDType,
        baseTypeName,
        reflectedTypeName,
        rootObjectField,
        argumentsTypeName,
        pathFromQueryRoot);
  }

  /** Extracts description from a type definition. Returns null for now. */
  private String getDescription(ViaductSchema.TypeDef typeDef) {
    // ViaductSchema doesn't expose description directly in the interface.
    // Description could be accessed through the underlying data if needed.
    return null;
  }
}
