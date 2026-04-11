package viaduct.x.javaapi.codegen;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

        // Collect all fields (extensions are already merged), skipping BackingData-typed
        // fields (opaque at the GRT level, mirrors Kotlin's codegenIncludedFields in
        // BackingData.kt)
        List<FieldModel> fields = new ArrayList<>();
        for (ViaductSchema.Field field : objectDef.getFields()) {
          if (isBackingDataField(field)) continue;
          fields.add(createFieldModel(field, typeMapper));
        }

        objects.add(
            new ObjectModel(
                packageName,
                name,
                interfaces,
                fields,
                getDescription(objectDef),
                rootTypes.contains(name)));
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

        // Collect all fields (extensions are already merged), excluding BackingData
        List<FieldModel> fields = new ArrayList<>();
        for (ViaductSchema.Field field : inputDef.getFields()) {
          if (isBackingDataField(field)) continue;
          fields.add(createFieldModel(field, typeMapper));
        }

        inputs.add(new InputModel(packageName, name, fields, getDescription(inputDef)));
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

        // Collect all fields (extensions are already merged), excluding BackingData
        List<FieldModel> fields = new ArrayList<>();
        for (ViaductSchema.Field field : interfaceDef.getFields()) {
          if (isBackingDataField(field)) continue;
          fields.add(createFieldModel(field, typeMapper));
        }

        interfaces.add(
            new InterfaceModel(
                packageName, name, extendedInterfaces, fields, getDescription(interfaceDef)));
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
    Map<String, List<ResolverModel>> resolversByType =
        extractResolvers(schema, packageName, mutationTypeName);
    TypeMapper typeMapper = new TypeMapper();
    List<ArgumentModel> arguments = new ArrayList<>();

    for (ViaductSchema.TypeDef typeDef : schema.getTypes().values()) {
      if (!(typeDef instanceof ViaductSchema.Object objectType)) continue;
      String typeName = objectType.getName();

      List<ResolverModel> resolvers = resolversByType.get(typeName);
      if (resolvers == null) continue;

      for (ResolverModel resolver : resolvers) {
        if (!resolver.hasArguments()) continue;

        for (ViaductSchema.Extension<?, ?> extension : objectType.getExtensions()) {
          for (Object member : extension.getMembers()) {
            if (!(member instanceof ViaductSchema.Field field)) continue;
            if (!field.getName().equals(resolver.gqlFieldName())) continue;
            if (!field.getHasArgs()) continue;

            String fqClassName = resolver.argumentsType();
            String className = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
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
              fields.add(
                  new FieldModel(
                      arg.getName(),
                      typeMapper.toJavaType(arg.getType()),
                      arg.getType().isNullable(),
                      argCompositeType,
                      argList,
                      argEnumType,
                      argAbstractType,
                      argBaseTypeName));
            }
            arguments.add(new ArgumentModel(packageName, className, fields));
          }
        }
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
                  createResolverModel(field, typeName, grtPackage, typeMapper, mutationTypeName);
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
   * @return the resolver model
   */
  private ResolverModel createResolverModel(
      ViaductSchema.Field field,
      String typeName,
      String grtPackage,
      TypeMapper typeMapper,
      String mutationTypeName) {
    String fieldName = field.getName();
    String resolverClassName = capitalize(fieldName);

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
            ? grtPackage + "." + typeName + "_" + resolverClassName + "_Arguments"
            : "Arguments.None";

    // Selections type - use CompositeOutput.None if output is not a composite type
    boolean isCompositeOutput = field.getType().getBaseTypeDef().isComposite();
    String selectionsType =
        isCompositeOutput
            ? grtPackage + "." + field.getType().getBaseTypeDef().getName()
            : "CompositeOutput.None";
    boolean isSelective = isSelectiveResolver(field);

    // Exclude batchResolve for Mutation fields
    boolean includeBatchResolve = !typeName.equals(mutationTypeName);

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
        includeBatchResolve);
  }

  private boolean isSelectiveResolver(ViaductSchema.Def def) {
    return def.getAppliedDirectives().stream()
        .filter(directive -> directive.getName().equals("resolver"))
        .findFirst()
        .map(
            directive -> {
              ViaductSchema.Literal isSelectiveArg = directive.getArguments().get("isSelective");
              if (isSelectiveArg == null) {
                isSelectiveArg = directive.getArguments().get("selective");
              }
              if (isSelectiveArg == null) {
                return false;
              }
              if (isSelectiveArg instanceof ViaductSchema.BooleanLiteral booleanLiteral) {
                return booleanLiteral.getValue();
              }
              throw new IllegalArgumentException(
                  "Expected @resolver(isSelective:/selective:) to decode as a boolean on "
                      + def.describe());
            })
        .orElse(false);
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
   * Returns true if the field's base type is the BackingData scalar. BackingData fields are
   * excluded from GRT codegen — they are opaque containers whose runtime type is specified
   * per-field via the @backingData directive. Mirrors Kotlin's codegenIncludedFields in
   * BackingData.kt.
   */
  private boolean isBackingDataField(ViaductSchema.Field field) {
    ViaductSchema.TypeDef baseDef = field.getType().getBaseTypeDef();
    return baseDef.getKind() == ViaductSchema.TypeDefKind.SCALAR
        && baseDef.getName().equals("BackingData");
  }

  /** Creates a FieldModel from a ViaductSchema.Field. */
  private FieldModel createFieldModel(ViaductSchema.Field field, TypeMapper typeMapper) {
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
    return new FieldModel(
        field.getName(),
        javaType,
        nullable,
        compositeType,
        list,
        enumType,
        abstractType,
        baseTypeName);
  }

  /** Extracts description from a type definition. Returns null for now. */
  private String getDescription(ViaductSchema.TypeDef typeDef) {
    // ViaductSchema doesn't expose description directly in the interface.
    // Description could be accessed through the underlying data if needed.
    return null;
  }

  /** Capitalizes the first character of a string. */
  private static String capitalize(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
