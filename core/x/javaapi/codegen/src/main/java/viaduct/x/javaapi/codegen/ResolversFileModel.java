package viaduct.x.javaapi.codegen;

import java.util.List;

/**
 * Model representing a resolvers file for code generation.
 *
 * <p>Each resolvers file model contains all resolvers for a single GraphQL type. The generated file
 * will be named {TypeName}Resolvers.java and will contain inner classes for each resolver.
 *
 * @param packageName tenant package for the resolver bases ({packageName}.resolverbases)
 * @param grtPackage package of the generated GRT types imported by the resolver bases
 * @param typeName GraphQL type name (e.g. "Query")
 * @param resolvers individual resolver models for each field
 */
public record ResolversFileModel(
    String packageName, String grtPackage, String typeName, List<ResolverModel> resolvers) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getGrtPackage() {
    return grtPackage;
  }

  public String getTypeName() {
    return typeName;
  }

  public List<ResolverModel> getResolvers() {
    return resolvers;
  }

  public boolean getHasBatchingResolvers() {
    return resolvers.stream().anyMatch(ResolverModel::isBatching);
  }

  public boolean getHasUnbatchedResolvers() {
    return resolvers.stream().anyMatch(resolver -> !resolver.isBatching());
  }
}
