package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    assertTrue(generated.contains("package com.example.tenant.resolverbases;"));
    assertTrue(generated.contains("public final class NodeResolvers"));
  }

  @Test
  void generate_importsGrtPackageSeparatelyFromTenantPackage() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    // Package declaration uses tenantPackage, GRT import uses grtPackage
    assertTrue(generated.contains("package com.example.tenant.resolverbases;"));
    assertTrue(generated.contains("import com.example.types.*;"));
  }

  @Test
  void generate_producesNodeResolverForAnnotation() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(
        generated.contains(
            "@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = false)"));
  }

  @Test
  void generate_producesResolveMethod_forNonBatchingResolver() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(
        generated.contains(
            "public abstract CompletableFuture<com.example.types.User> resolve(Context ctx)"));
    assertTrue(
        generated.contains(
            "implements NodeResolverBase<com.example.types.User>, BaseUnbatchedNodeResolver"));
    assertTrue(generated.contains("public final CompletableFuture<?> invokeNodeResolver("));
    assertTrue(
        generated.contains("return resolve(new Context((NodeExecutionContext<?>) context))"));
    assertTrue(!generated.contains("batchResolve"));
  }

  @Test
  void generate_producesBatchResolveMethod_forBatchingResolver() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("Booking", true, false)));

    // Mirrors Kotlin: Map<Context, FieldValue<T>> wrapped in CompletableFuture
    assertTrue(
        generated.contains(
            "public abstract CompletableFuture<Map<Context, FieldValue<com.example.types.Booking>>>"
                + " batchResolve(List<Context> contexts)"));
    assertTrue(
        generated.contains(
            "implements NodeResolverBase<com.example.types.Booking>,"
                + " BaseBatchedNodeResolver<com.example.types.Booking>"));
    assertTrue(generated.contains("invokeNodeBatchResolver("));
    assertTrue(generated.contains("List<NodeExecutionContext<?>> contexts"));
    assertTrue(
        generated.contains("wrappedContexts.add(new Context((NodeExecutionContext<?>) context))"));
    assertTrue(generated.contains("unwrappedResults.put(resultContext.inner, value)"));
    assertTrue(
        !generated.contains("CompletableFuture<com.example.types.Booking> resolve(Context ctx)"));
  }

  @Test
  void generate_producesContextImplementingNodeExecutionContext_whenNotSelective() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(
        generated.contains(
            "public static final class Context implements"
                + " NodeExecutionContext<com.example.types.User>,"
                + " NodeResolverBase.Context<com.example.types.User>"));
    assertTrue(!generated.contains("SelectiveNodeExecutionContext<com.example.types.User>"));
  }

  @Test
  void generate_producesContextImplementingSelectiveNodeExecutionContext_whenSelective() {
    String generated = JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, true)));

    assertTrue(
        generated.contains(
            "public static final class Context implements"
                + " SelectiveNodeExecutionContext<com.example.types.User>,"
                + " NodeResolverBase.Context<com.example.types.User>"));
    assertTrue(generated.contains("public Object selections()"));
    assertTrue(
        generated.contains(
            "return resolve(new Context((SelectiveNodeExecutionContext<?>) context))"));
  }

  @Test
  void generate_castsBatchInvokerContext_whenSelective() {
    String generated = JavaNodeResolverGenerator.generate(fileModel(resolver("User", true, true)));

    assertTrue(generated.contains("new Context((SelectiveNodeExecutionContext<?>) context)"));
  }

  @Test
  void generate_doesNotEmitSelectionsMethod_whenNotSelective() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(!generated.contains("public Object selections()"));
  }

  @Test
  void generate_contextDelegatesAllNodeExecutionContextMethods() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(generated.contains("public GlobalID<com.example.types.User> getId()"));
    assertTrue(generated.contains("public Object getRequestContext()"));
    assertTrue(
        generated.contains(
            "public <T extends NodeObject> String globalIDStringFor(Type<T> type, String"
                + " internalID)"));
    assertTrue(
        generated.contains("public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor("));
    assertTrue(
        generated.contains(
            "public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID)"));
    assertTrue(
        generated.contains("public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id)"));
    assertTrue(
        generated.contains(
            "public <T> CompletableFuture<T> query(String selections,"
                + " Map<String, Object> variables, Class<T> targetClass)"));
    assertTrue(
        generated.contains(
            "public <T> CompletableFuture<T> mutation(String selections,"
                + " Map<String, Object> variables, Class<T> targetClass)"));
    assertTrue(
        generated.contains(
            "public <T> CompletableFuture<T> query(QueryFromAnnotation operation,"
                + " Map<String, Object> variables, Class<T> targetClass)"));
    assertTrue(
        generated.contains(
            "public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation,"
                + " Map<String, Object> variables, Class<T> targetClass)"));
  }

  @Test
  void generate_producesCorrectSelectiveLiteral() {
    String generated = JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, true)));

    assertTrue(
        generated.contains(
            "@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = true)"));
  }

  @Test
  void generate_producesMultipleNodeResolvers() {
    String generated =
        JavaNodeResolverGenerator.generate(
            fileModel(resolver("User", false, false), resolver("Booking", true, false)));

    assertTrue(
        generated.contains(
            "@NodeResolverFor(typeName = \"User\", isBatching = false, isSelective = false)"));
    assertTrue(generated.contains("public abstract static class User"));
    assertTrue(
        generated.contains(
            "@NodeResolverFor(typeName = \"Booking\", isBatching = true, isSelective = false)"));
    assertTrue(generated.contains("public abstract static class Booking"));
  }

  @Test
  void generate_importsNodeResolverBaseAndFieldValue() {
    String generated =
        JavaNodeResolverGenerator.generate(fileModel(resolver("User", false, false)));

    assertTrue(generated.contains("import viaduct.java.api.resolvers.NodeResolverBase;"));
    assertTrue(generated.contains("import viaduct.java.api.resolvers.FieldValue;"));
    assertTrue(generated.contains("import viaduct.java.api.context.NodeExecutionContext;"));
    assertTrue(
        generated.contains("import viaduct.java.api.context.SelectiveNodeExecutionContext;"));
  }

  @Test
  void generateToFile_writesFileToCorrectLocation(@TempDir Path tempDir) throws IOException {
    File outputFile =
        JavaNodeResolverGenerator.generateToFile(
            fileModel(resolver("User", false, false)), tempDir.toFile());

    assertNotNull(outputFile);
    assertEquals("NodeResolvers.java", outputFile.getName());
    assertTrue(outputFile.exists());

    String content = Files.readString(outputFile.toPath());
    assertTrue(content.contains("package com.example.tenant.resolverbases;"));
  }

  @Test
  void generateToFile_returnsNull_whenNoNodeResolvers(@TempDir Path tempDir) throws IOException {
    JavaNodeResolverGenerator.FileModel model =
        new JavaNodeResolverGenerator.FileModel(
            "com.example.tenant", "com.example.types", List.of());

    File outputFile = JavaNodeResolverGenerator.generateToFile(model, tempDir.toFile());

    assertNull(outputFile);
  }
}
