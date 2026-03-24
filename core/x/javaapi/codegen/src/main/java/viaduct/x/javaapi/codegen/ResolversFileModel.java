package viaduct.x.javaapi.codegen;

import java.util.List;

/**
 * Model representing a resolvers file for code generation.
 *
 * <p>Each resolvers file model contains all resolvers for a single GraphQL type. The generated file
 * will be named {TypeName}Resolvers.java and will contain inner classes for each resolver.
 */
public record ResolversFileModel(
    String packageName, String typeName, List<ResolverModel> resolvers) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getTypeName() {
    return typeName;
  }

  public List<ResolverModel> getResolvers() {
    return resolvers;
  }
}
