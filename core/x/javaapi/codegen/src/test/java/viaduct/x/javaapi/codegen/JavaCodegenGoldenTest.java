package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import viaduct.graphql.utils.DefaultSchemaFactory;
import viaduct.graphql.utils.Predicates;
import viaduct.graphql.utils.TypeDefinitionRegistryExtensionsKt;

/**
 * Golden-output characterization test for the Java GRT and resolver code generators.
 *
 * <p>This test regenerates Java source from {@code golden/golden_schema.graphqls} and asserts that
 * every generated file is byte-for-byte identical to the checked-in golden snapshot under {@code
 * src/test/resources/golden/expected/}. It exists to lock in the <em>current</em> generator output
 * so that a behavior-preserving refactor (e.g. sharing schema-analysis logic between the Java and
 * Kotlin codegens) can be proven to leave the output unchanged.
 *
 * <p>The fixture deliberately exercises every construct the generator branches on: plain objects,
 * Node types and {@code Node.id}, connections/edges ({@code @connection}/{@code @edge}), {@code
 * @idOf}, {@code BackingData} fields, unions, interfaces (including an interface that extends
 * {@code Node}), enums (with and without descriptions), input types, and resolvers in every variant
 * (field, mutation, connection, batching, selective, and node).
 *
 * <h2>Regenerating the golden files</h2>
 *
 * If a deliberate codegen change alters the output, regenerate the golden snapshot by running the
 * test with {@code -Dviaduct.codegen.golden.regenerate=true}, then review the diff under {@code
 * src/test/resources/golden/expected/}. An <em>unexpected</em> diff is a real signal: the generator
 * changed behavior. From the {@code oss} directory:
 *
 * <pre>
 * ./gradlew -p core :x:javaapi:codegen:test \
 *     --tests "viaduct.x.javaapi.codegen.JavaCodegenGoldenTest" \
 *     -Dviaduct.codegen.golden.regenerate=true
 * </pre>
 */
class JavaCodegenGoldenTest {

  private static final String SCHEMA_RESOURCE = "golden/golden_schema.graphqls";
  private static final String GRT_PACKAGE = "com.example.grts";
  private static final String TENANT_PACKAGE = "com.example.tenant";
  private static final String REGENERATE_PROPERTY = "viaduct.codegen.golden.regenerate";

  /**
   * Classpath prefix under which the golden snapshots are packaged as test resources. Goldens are
   * read from the classpath so the verification path works identically under Gradle and Bazel (the
   * latter runs tests from a sandbox whose working directory is not the module directory).
   */
  private static final String GOLDEN_RESOURCE_PREFIX = "golden/expected";

  /**
   * Source-tree location of the golden files, used only by the {@code -D…regenerate=true} path so
   * regeneration writes to the checked-in resources. This is resolved relative to the module
   * working directory, which is only well-defined under Gradle; regeneration is always run via
   * Gradle.
   */
  private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden", "expected");

  @TempDir Path tempDir;

  @Test
  void grtAndResolverOutputMatchesGoldenSnapshot() throws IOException {
    Path schemaFile = writeSchemaWithDefaults();

    Path outputDir = tempDir.resolve("generated");
    Files.createDirectories(outputDir);

    new JavaGRTsCodegen()
        .generate(
            List.of(schemaFile.toFile()),
            outputDir.toFile(),
            GRT_PACKAGE,
            /* includeRootTypes= */ true);
    new JavaResolversCodegen()
        .generate(List.of(schemaFile.toFile()), outputDir.toFile(), GRT_PACKAGE, TENANT_PACKAGE);

    List<GeneratedFile> generated = readGeneratedTree(outputDir);

    if (isRegenerating()) {
      rewriteGoldenFiles(generated);
      return;
    }

    assertOutputMatchesGolden(generated);
  }

  /** A generated source file, keyed by its path relative to the output root. */
  private record GeneratedFile(String relativePath, String content) {}

  private List<GeneratedFile> readGeneratedTree(Path outputDir) throws IOException {
    try (Stream<Path> paths = Files.walk(outputDir)) {
      return paths
          .filter(Files::isRegularFile)
          .sorted()
          .map(
              p -> {
                try {
                  String rel = outputDir.relativize(p).toString().replace('\\', '/');
                  return new GeneratedFile(rel, Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                  throw new RuntimeException("Failed to read generated file " + p, e);
                }
              })
          .collect(Collectors.toList());
    }
  }

  private void assertOutputMatchesGolden(List<GeneratedFile> generated) throws IOException {
    List<String> goldenPaths = listGoldenRelativePaths();
    List<String> generatedPaths =
        generated.stream().map(GeneratedFile::relativePath).sorted().collect(Collectors.toList());

    // The set of generated files is itself part of the contract: a refactor that adds or drops a
    // file changes behavior, so compare the file sets before comparing contents.
    assertThat(generatedPaths)
        .as(
            "Set of generated files differs from golden snapshot. If this is an intentional "
                + "codegen change, regenerate with -D%s=true.",
            REGENERATE_PROPERTY)
        .containsExactlyInAnyOrderElementsOf(goldenPaths);

    for (GeneratedFile file : generated) {
      String golden = readGolden(file.relativePath());
      assertThat(file.content())
          .as(
              "Generated %s differs from golden snapshot. If this is an intentional codegen "
                  + "change, regenerate with -D%s=true.",
              file.relativePath(), REGENERATE_PROPERTY)
          .isEqualTo(golden);
    }
  }

  // ===== Golden-file IO =====

  private boolean isRegenerating() {
    return Boolean.parseBoolean(System.getProperty(REGENERATE_PROPERTY, "false"));
  }

  private void rewriteGoldenFiles(List<GeneratedFile> generated) throws IOException {
    if (Files.exists(GOLDEN_DIR)) {
      try (Stream<Path> paths = Files.walk(GOLDEN_DIR)) {
        paths.sorted(Comparator.reverseOrder()).forEach(JavaCodegenGoldenTest::deleteQuietly);
      }
    }
    Files.createDirectories(GOLDEN_DIR);
    for (GeneratedFile file : generated) {
      Path dest = GOLDEN_DIR.resolve(file.relativePath());
      Files.createDirectories(dest.getParent());
      Files.writeString(dest, file.content(), StandardCharsets.UTF_8);
    }
  }

  private List<String> listGoldenRelativePaths() {
    try (ScanResult scan = new ClassGraph().acceptPaths(GOLDEN_RESOURCE_PREFIX).scan()) {
      return scan.getAllResources().stream()
          .map(r -> stripPrefix(r.getPath()))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private String readGolden(String relativePath) throws IOException {
    String resourcePath = GOLDEN_RESOURCE_PREFIX + "/" + relativePath;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Golden not found on classpath: " + resourcePath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String stripPrefix(String resourcePath) {
    return resourcePath.substring(GOLDEN_RESOURCE_PREFIX.length() + 1);
  }

  /**
   * Loads the fixture schema and applies the framework defaults (the {@code @resolver}, {@code
   * @idOf}, {@code @backingData}, {@code @connection}, {@code @edge} directives, the {@code Node}
   * interface, the {@code PageInfo} type, standard scalars, and the {@code Query}/{@code Mutation}
   * root types). This mirrors how the codegen is driven in production and in {@link
   * JavaResolversCodegenTest}, so the fixture only needs to declare the domain types.
   */
  private Path writeSchemaWithDefaults() throws IOException {
    String raw;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found on classpath: " + SCHEMA_RESOURCE);
      }
      raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    TypeDefinitionRegistry registry = new SchemaParser().parse(raw);
    DefaultSchemaFactory.INSTANCE.addDefaults(
        registry,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        false,
        false,
        false);

    String sdl =
        TypeDefinitionRegistryExtensionsKt.toSDL(
            registry, Predicates.INSTANCE.alwaysTrue(), Predicates.INSTANCE.alwaysTrue());

    Path dest = tempDir.resolve("schema.graphqls");
    Files.writeString(dest, sdl, StandardCharsets.UTF_8);
    return dest;
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new RuntimeException("Failed to delete " + path, e);
    }
  }
}
