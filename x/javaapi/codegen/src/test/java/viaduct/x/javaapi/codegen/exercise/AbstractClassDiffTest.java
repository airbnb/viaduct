package viaduct.x.javaapi.codegen.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import viaduct.codegen.km.ctdiff.ClassDiff;
import viaduct.codegen.km.ctdiff.ClassFinder;
import viaduct.codegen.km.ctdiff.JavaClassLoaderClassFinder;
import viaduct.invariants.FailureCollector;
import viaduct.x.javaapi.codegen.JavaGRTsCodegen;

/**
 * Abstract base class for ClassDiff tests that compare generated GRT classes against expected
 * reference classes.
 *
 * <p>The test flow:
 *
 * <ol>
 *   <li>Load GraphQL schema from test resources
 *   <li>Generate Java source files using JavaGRTsCodegen
 *   <li>Compile the generated source files
 *   <li>Load the compiled classes with a custom ClassLoader
 *   <li>Use ClassDiff with JavaClassLoaderClassFinder to compare generated vs expected classes
 * </ol>
 *
 * <p>Subclasses provide the schema resource path via {@link #getSchemaResource()}.
 */
abstract class AbstractClassDiffTest {

  protected static final String GENERATED_PACKAGE = "viaduct.x.javaapi.codegen.exercise.generated";
  protected static final String EXPECTED_PACKAGE = "viaduct.x.javaapi.codegen.exercise.grts";

  @TempDir Path tempDir;

  protected JavaGRTsCodegen codegen;
  protected FailureCollector diffs;

  /** Returns the classpath resource path for the GraphQL schema file. */
  protected abstract String getSchemaResource();

  @BeforeEach
  void setUp() {
    codegen = new JavaGRTsCodegen();
    diffs = new FailureCollector();
  }

  /**
   * Exercises multiple types by generating, compiling, and comparing them against expected classes.
   *
   * @param typeNames list of simple class names to compare
   */
  protected void exerciseTypes(List<String> typeNames) throws Exception {
    ClassFinder classFinder = generateAndCompile();
    ClassDiff classDiff = new ClassDiff(EXPECTED_PACKAGE, GENERATED_PACKAGE, diffs, classFinder);

    for (String typeName : typeNames) {
      Class<?> expectedClass = Class.forName(EXPECTED_PACKAGE + "." + typeName);
      Class<?> generatedClass = classFinder.find(GENERATED_PACKAGE + "." + typeName);
      classDiff.compare(expectedClass, generatedClass);
    }

    assertNoDiffs();
  }

  /**
   * Exercises a single type by generating, compiling, and comparing it against the expected class.
   *
   * @param typeName the simple class name to compare
   */
  protected void exerciseSingleType(String typeName) throws Exception {
    ClassFinder classFinder = generateAndCompile();
    ClassDiff classDiff = new ClassDiff(EXPECTED_PACKAGE, GENERATED_PACKAGE, diffs, classFinder);

    Class<?> expectedClass = Class.forName(EXPECTED_PACKAGE + "." + typeName);
    Class<?> generatedClass = classFinder.find(GENERATED_PACKAGE + "." + typeName);
    classDiff.compare(expectedClass, generatedClass);

    assertNoDiffs(typeName);
  }

  /**
   * Generates Java sources from the schema and compiles them, returning a ClassFinder for the
   * compiled classes.
   */
  private ClassFinder generateAndCompile() throws Exception {
    File schemaFile = extractSchemaToTempFile();

    Path generatedSourceDir = tempDir.resolve("generated-sources");
    Files.createDirectories(generatedSourceDir);
    codegen.generate(List.of(schemaFile), generatedSourceDir.toFile(), GENERATED_PACKAGE);

    Path compiledClassesDir = tempDir.resolve("compiled-classes");
    Files.createDirectories(compiledClassesDir);
    compileGeneratedSources(generatedSourceDir, compiledClassesDir);

    URLClassLoader generatedClassLoader =
        new URLClassLoader(
            new URL[] {compiledClassesDir.toUri().toURL()}, getClass().getClassLoader());

    return new JavaClassLoaderClassFinder(generatedClassLoader);
  }

  private void assertNoDiffs() {
    if (!diffs.isEmpty()) {
      StringBuilder result = new StringBuilder("Exerciser failures:\n");
      diffs.toMultilineString(result);
      System.err.println(result);
    }

    assertThat(diffs.isEmpty())
        .withFailMessage(
            "Expected no failures, but found:\n%s", String.join("\n", diffs.toListOfErrors()))
        .isTrue();
  }

  private void assertNoDiffs(String typeName) {
    if (!diffs.isEmpty()) {
      StringBuilder result = new StringBuilder("Exerciser failures for " + typeName + ":\n");
      diffs.toMultilineString(result);
      System.err.println(result);
    }

    assertThat(diffs.isEmpty())
        .withFailMessage(
            "Expected no failures for %s, but found:\n%s",
            typeName, String.join("\n", diffs.toListOfErrors()))
        .isTrue();
  }

  private File extractSchemaToTempFile() throws IOException {
    String schemaResource = getSchemaResource();
    InputStream schemaStream = getClass().getClassLoader().getResourceAsStream(schemaResource);
    if (schemaStream == null) {
      throw new IOException("Schema resource not found: " + schemaResource);
    }

    Path schemaFile = tempDir.resolve("schema.graphqls");
    Files.copy(schemaStream, schemaFile);
    return schemaFile.toFile();
  }

  private void compileGeneratedSources(Path sourceDir, Path outputDir) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
          "No Java compiler available. Make sure you're running with a JDK, not just a JRE.");
    }

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(diagnostics, null, null)) {

      List<File> javaFiles =
          Files.walk(sourceDir)
              .filter(p -> p.toString().endsWith(".java"))
              .map(Path::toFile)
              .toList();

      if (javaFiles.isEmpty()) {
        throw new IOException("No Java files found in " + sourceDir);
      }

      Iterable<? extends JavaFileObject> compilationUnits =
          fileManager.getJavaFileObjectsFromFiles(javaFiles);

      String classpath = System.getProperty("java.class.path");
      List<String> options = Arrays.asList("-d", outputDir.toString(), "-classpath", classpath);

      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

      boolean success = task.call();

      if (!success) {
        StringBuilder errorMessage = new StringBuilder("Compilation failed:\n");
        diagnostics
            .getDiagnostics()
            .forEach(
                d ->
                    errorMessage
                        .append(d.getKind())
                        .append(": ")
                        .append(d.getMessage(null))
                        .append("\n"));
        throw new IOException(errorMessage.toString());
      }
    }
  }
}
