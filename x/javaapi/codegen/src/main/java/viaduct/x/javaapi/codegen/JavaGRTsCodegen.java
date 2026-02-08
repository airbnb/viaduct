package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import viaduct.graphql.schema.ViaductSchema;

/**
 * Main entry point for Java GRTs (GraphQL Representational Types) code generation. This class
 * handles the generation logic and can be called from CLI or programmatically. Uses ViaductSchema
 * as the schema abstraction layer.
 */
public class JavaGRTsCodegen {

  private final GraphQLSchemaParser parser;

  public JavaGRTsCodegen() {
    this.parser = new GraphQLSchemaParser();
  }

  /** Result of the code generation process. */
  public record Result(
      int enumCount,
      int objectCount,
      int inputCount,
      int interfaceCount,
      int unionCount,
      List<File> generatedFiles) {
    public int totalCount() {
      return enumCount + objectCount + inputCount + interfaceCount + unionCount;
    }
  }

  /**
   * Generates Java GRTs from GraphQL schema files.
   *
   * <p>GRT types are written to {@code grtOutputDir} in package subdirectories.
   *
   * @param schemaFiles list of GraphQL schema files to parse
   * @param grtOutputDir output directory for generated GRT files (written to package subdirs)
   * @param grtPackage Java package name for generated GRT types
   * @return result containing counts of generated types
   * @throws IOException if there's an error reading or writing files
   */
  public Result generate(List<File> schemaFiles, File grtOutputDir, String grtPackage)
      throws IOException {
    // Ensure output directory exists
    if (!grtOutputDir.exists() && !grtOutputDir.mkdirs()) {
      throw new IOException("Failed to create GRT output directory: " + grtOutputDir);
    }

    // Parse schemas into ViaductSchema
    ViaductSchema schema = parser.parse(schemaFiles);

    List<File> generatedFiles = new ArrayList<>();

    // Generate enums
    List<EnumModel> enumModels = parser.extractEnums(schema, grtPackage);
    for (EnumModel model : enumModels) {
      generatedFiles.add(JavaGRTGenerator.EnumGenerator.generateToFile(model, grtOutputDir));
    }

    // Generate objects
    List<ObjectModel> objectModels = parser.extractObjects(schema, grtPackage);
    for (ObjectModel model : objectModels) {
      generatedFiles.add(JavaGRTGenerator.ObjectGenerator.generateToFile(model, grtOutputDir));
    }

    // Generate inputs
    List<InputModel> inputModels = parser.extractInputs(schema, grtPackage);
    for (InputModel model : inputModels) {
      generatedFiles.add(JavaGRTGenerator.InputGenerator.generateToFile(model, grtOutputDir));
    }

    // Generate interfaces
    List<InterfaceModel> interfaceModels = parser.extractInterfaces(schema, grtPackage);
    for (InterfaceModel model : interfaceModels) {
      generatedFiles.add(JavaGRTGenerator.InterfaceGenerator.generateToFile(model, grtOutputDir));
    }

    // Generate unions
    List<UnionModel> unionModels = parser.extractUnions(schema, grtPackage);
    for (UnionModel model : unionModels) {
      generatedFiles.add(JavaGRTGenerator.UnionGenerator.generateToFile(model, grtOutputDir));
    }

    return new Result(
        enumModels.size(),
        objectModels.size(),
        inputModels.size(),
        interfaceModels.size(),
        unionModels.size(),
        generatedFiles);
  }
}
