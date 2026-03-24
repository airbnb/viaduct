package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import viaduct.graphql.schema.ViaductSchema;

/**
 * Main entry point for Java resolver base classes code generation. This class handles the
 * generation of resolver base classes separately from GRTs (GraphQL Representational Types).
 *
 * <p>Uses ViaductSchema as the schema abstraction layer.
 */
public class JavaResolversCodegen {

  private final GraphQLSchemaParser parser;

  public JavaResolversCodegen() {
    this.parser = new GraphQLSchemaParser();
  }

  /** Result of the resolver code generation process. */
  public record Result(int resolverFileCount, int resolverCount, List<File> generatedFiles) {}

  /**
   * Generates Java resolver base classes from GraphQL schema files.
   *
   * <p>Resolver files are written to {@code resolverOutputDir} in package subdirectories. The
   * package is {@code {tenantPackage}.resolverbases}.
   *
   * @param schemaFiles list of GraphQL schema files to parse
   * @param resolverOutputDir output directory for resolver files
   * @param grtPackage Java package name for GRT types (needed to map GraphQL types to Java types)
   * @param tenantPackage Java package name for resolver bases (resolvers use
   *     {tenantPackage}.resolverbases)
   * @return result containing counts of generated resolver types
   * @throws IOException if there's an error reading or writing files
   */
  public Result generate(
      List<File> schemaFiles, File resolverOutputDir, String grtPackage, String tenantPackage)
      throws IOException {
    // Ensure output directory exists
    if (!resolverOutputDir.exists() && !resolverOutputDir.mkdirs()) {
      throw new IOException("Failed to create resolver output directory: " + resolverOutputDir);
    }

    // Parse schemas into ViaductSchema
    ViaductSchema schema = parser.parse(schemaFiles);

    List<File> generatedFiles = new ArrayList<>();

    // Generate resolver base classes
    int resolverCount = 0;
    String mutationTypeName =
        schema.getMutationTypeDef() != null ? schema.getMutationTypeDef().getName() : null;
    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, grtPackage, mutationTypeName);

    for (Map.Entry<String, List<ResolverModel>> entry : resolversByType.entrySet()) {
      String typeName = entry.getKey();
      List<ResolverModel> resolvers = entry.getValue();

      ResolversFileModel fileModel = new ResolversFileModel(tenantPackage, typeName, resolvers);
      generatedFiles.add(JavaResolverGenerator.generateToFile(fileModel, resolverOutputDir));
      resolverCount += resolvers.size();
    }

    return new Result(resolversByType.size(), resolverCount, generatedFiles);
  }
}
