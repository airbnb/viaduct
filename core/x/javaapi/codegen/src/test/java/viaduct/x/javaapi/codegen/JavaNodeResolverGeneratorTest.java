package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaNodeResolverGeneratorTest {

  private static JavaNodeResolverGenerator.FileModel fileModel(NodeResolverModel... resolvers) {
    return new JavaNodeResolverGenerator.FileModel(
        "com.example.tenant", "com.example.types", List.of(resolvers));
  }

  private static NodeResolverModel resolver(String typeName, boolean batching, boolean selective) {
    return new NodeResolverModel(
        "com.example.tenant", "com.example.types", typeName, batching, selective);
  }

  @Test
  void generate_producesCorrectPackageAndClass() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains("package com.example.tenant.resolverbases;")
        .contains("public final class NodeResolvers");
  }

  @Test
  void generate_importsGrtPackageSeparatelyFromTenantPackage() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    // Package declaration uses tenantPackage, GRT import uses grtPackage
    assertThat(generated)
        .contains("package com.example.tenant.resolverbases;")
        .contains("import com.example.types.*;");
  }

  @Test
  void generate_producesNodeResolverForAnnotation() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains("@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = false)");
  }

  @Test
  void generate_producesResolveMethod_forNonBatchingResolver() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains("public abstract CompletableFuture<com.example.types.User> resolve(Context ctx)")
        .doesNotContain("batchResolve");
  }

  @Test
  void generate_producesBatchResolveMethod_forBatchingResolver() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("Booking", true, false)));

    // Mirrors Kotlin: List<FieldValue<T>> wrapped in CompletableFuture
    assertThat(generated)
        .contains(
            "public abstract CompletableFuture<List<FieldValue<com.example.types.Booking>>>"
                + " batchResolve(List<Context> contexts)")
        .doesNotContain("CompletableFuture<com.example.types.Booking> resolve(Context ctx)");
  }

  @Test
  void generate_producesContextImplementingNodeExecutionContext_whenNotSelective() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains(
            "public static final class Context implements"
                + " NodeExecutionContext<com.example.types.User>,"
                + " NodeResolverBase.Context<com.example.types.User>")
        .doesNotContain("SelectiveNodeExecutionContext<com.example.types.User>");
  }

  @Test
  void generate_producesContextImplementingSelectiveNodeExecutionContext_whenSelective() {
    String generated = JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, true)));

    assertThat(generated)
        .contains(
            "public static final class Context implements"
                + " SelectiveNodeExecutionContext<com.example.types.User>,"
                + " NodeResolverBase.Context<com.example.types.User>")
        .contains("public Object selections()");
  }

  @Test
  void generate_doesNotEmitSelectionsMethod_whenNotSelective() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated).doesNotContain("public Object selections()");
  }

  @Test
  void generate_contextDelegatesAllNodeExecutionContextMethods() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains("public GlobalID<com.example.types.User> getId()")
        .contains("public Object getRequestContext()")
        .contains(
            "public <T extends NodeObject> String globalIDStringFor(Type<T> type, String"
                + " internalID)")
        .contains("public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(")
        .contains("public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID)")
        .contains("public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id)")
        .contains(
            "public <T> CompletableFuture<T> query(String selections,"
                + " Map<String, Object> variables, Class<T> targetClass)")
        .contains(
            "public <T> CompletableFuture<T> mutation(String selections,"
                + " Map<String, Object> variables, Class<T> targetClass)");
  }

  @Test
  void generate_producesCorrectSelectiveLiteral() {
    String generated = JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, true)));

    assertThat(generated)
        .contains("@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = true)");
  }

  @Test
  void generate_producesMultipleNodeResolvers() {
    String generated =
        JavaNodeResolverGenerator.generate(
            fileModel(resolver("User", false, false), resolver("Booking", true, false)));

    assertThat(generated)
        .contains("@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = false)")
        .contains("public abstract static class User")
        .contains(
            "@NodeResolverFor(typeName = \"Booking\", isBatching = true, isSelective = false)")
        .contains("public abstract static class Booking");
  }

  @Test
  void generate_importsNodeResolverBaseAndFieldValue() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertThat(generated)
        .contains("import viaduct.java.api.resolvers.NodeResolverBase;")
        .contains("import viaduct.java.api.resolvers.FieldValue;")
        .contains("import viaduct.java.api.context.NodeExecutionContext;")
        .contains("import viaduct.java.api.context.SelectiveNodeExecutionContext;");
  }

  @Test
  void generateToFile_writesFileToCorrectLocation(@TempDir Path tempDir) throws IOException {
    File outputFile =
        JavaNodeResolverGenerator.generateToFile(
            fileModel(resolver("User", false, false)), tempDir.toFile());

    assertThat(outputFile).isNotNull();
    assertThat(outputFile.getName()).isEqualTo("NodeResolvers.java");
    assertThat(outputFile.exists()).isTrue();

    String content = Files.readString(outputFile.toPath());
    assertThat(content).contains("package com.example.tenant.resolverbases;");
  }

  @Test
  void generateToFile_returnsNull_whenNoNodeResolvers(@TempDir Path tempDir) throws IOException {
    JavaNodeResolverGenerator.FileModel model =
        new JavaNodeResolverGenerator.FileModel(
            "com.example.tenant", "com.example.types", List.of());

    File outputFile = JavaNodeResolverGenerator.generateToFile(model, tempDir.toFile());

    assertThat(outputFile).isNull();
  }
}
