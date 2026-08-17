package com.example.execution.javaoneproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import viaduct.service.BasicViaductFactory;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.ExecutionResult;
import viaduct.service.api.Viaduct;

/**
 * End-to-end coverage of the Java module plugin in a single-project application: schema assembly,
 * Java resolver-base codegen, the APT-driven registry pipeline, and execution against a real
 * Viaduct instance bootstrapped from the packaged registry.
 */
public class PluginExecutionSmokeTest {

  private static final String REGISTRY_ENTRY =
      "META-INF/viaduct/modules/com.example.execution.javaoneproject.resolvers.json";

  private final Path buildDir = Path.of(System.getProperty("projectBuildDir"));

  @Test
  void generatedSchemaAndResolverOutputsExist() throws IOException {
    assertExists(buildDir.resolve("viaduct/centralSchema/BUILTIN_SCHEMA.graphqls"));
    Path baseSchemaFile = buildDir.resolve("viaduct/centralSchema/schemabase/directives.graphqls");
    Path commonSchemaFile = buildDir.resolve("viaduct/centralSchema/common/common.graphqls");
    Path partitionSchemaFile =
        buildDir.resolve("viaduct/centralSchema/partition/resolvers/graphql/schema.graphqls");

    assertExists(baseSchemaFile);
    assertExists(commonSchemaFile);
    assertExists(partitionSchemaFile);

    Path resolverBases =
        buildDir.resolve(
            "generated-sources/viaduct/javaResolverBases/"
                + "com/example/execution/javaoneproject/resolvers/resolverbases");
    assertExists(resolverBases.resolve("QueryResolvers.java"));
    assertExists(resolverBases.resolve("MutationResolvers.java"));

    assertTrue(Files.readString(baseSchemaFile).contains("directive @javaOneProjectBase"));
    assertTrue(Files.readString(commonSchemaFile).contains("directive @javaOneProjectCommon"));

    String partitionSchema = Files.readString(partitionSchemaFile);
    assertTrue(partitionSchema.contains("greeting: String"));
    assertTrue(partitionSchema.contains("echo(message: String!): String"));
  }

  @Test
  void tenantModuleConfigContainsAptExtractedResolvers() throws IOException {
    Path configFile = buildDir.resolve("generated-resources/viaduct-registry/" + REGISTRY_ENTRY);

    assertExists(configFile);

    String contents = Files.readString(configFile);
    assertTrue(contents.contains("GreetingResolver"));
    assertTrue(contents.contains("AuthorResolver"));
    assertTrue(contents.contains("EchoMutationResolver"));
    assertTrue(contents.contains("viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory"));
  }

  /**
   * javac's {@code Filer} writes the per-source descriptors into the class output rather than to a
   * dedicated codegen directory, so the assembled registry and these build intermediates share a
   * tree. Only the former belongs in the jar.
   */
  @Test
  void jarShipsAssembledRegistryWithoutRawDescriptors() throws IOException {
    assertExists(buildDir.resolve("classes/java/main/viaduct-registry"));

    String moduleJar = System.getProperty("moduleJar");
    try (JarFile jar = new JarFile(moduleJar)) {
      assertNotNull(
          jar.getJarEntry(REGISTRY_ENTRY),
          "Expected the assembled registry at " + REGISTRY_ENTRY + " in " + moduleJar);
      assertFalse(
          jar.stream().anyMatch(entry -> entry.getName().startsWith("viaduct-registry/")),
          "Expected no raw APT descriptors in " + moduleJar);
    }
  }

  @Test
  void queriesAndMutationsExecuteThroughViaduct() {
    Viaduct viaduct = BasicViaductFactory.create();

    ExecutionResult queryResult =
        viaduct.executeAsync(ExecutionInput.create("query { greeting author }")).join();
    assertTrue(
        queryResult.getErrors().isEmpty(),
        "Expected query execution without errors: " + queryResult.getErrors());
    assertEquals(
        Map.of(
            "greeting", "hello from java-one-project",
            "author", "gradletestapps"),
        queryResult.getData());

    ExecutionResult mutationResult =
        viaduct
            .executeAsync(ExecutionInput.create("mutation { echo(message: \"plugin e2e\") }"))
            .join();
    assertTrue(
        mutationResult.getErrors().isEmpty(),
        "Expected mutation execution without errors: " + mutationResult.getErrors());
    assertEquals(Map.of("echo", "plugin e2e"), mutationResult.getData());
  }

  private void assertExists(Path path) {
    assertTrue(Files.exists(path), "Expected generated output to exist: " + path);
  }
}
