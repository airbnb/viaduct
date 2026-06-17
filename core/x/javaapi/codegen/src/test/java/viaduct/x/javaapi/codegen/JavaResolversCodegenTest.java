package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graphql.schema.idl.SchemaParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import viaduct.graphql.utils.DefaultSchemaFactory;
import viaduct.graphql.utils.Predicates;
import viaduct.graphql.utils.TypeDefinitionRegistryExtensionsKt;

/** Tests for JavaResolversCodegen resolver-only code generation. */
class JavaResolversCodegenTest {

  @TempDir Path tempDir;

  private Path schemaFile;
  private JavaResolversCodegen codegen;

  @BeforeEach
  void setUp() throws IOException {
    codegen = new JavaResolversCodegen();

    // Create a test schema file with resolver fields
    String schema =
        """
        extend type Query {
          user(id: ID!): User @resolver
        }

        type User {
          id: ID!
          name: String!
          profile: Profile @resolver(isSelective: true)
        }

        type Profile {
          bio: String
        }
        """;

    schemaFile = writeSchemaWithDefaults("schema.graphqls", schema);
  }

  @Test
  void generatesResolverFilesToOutputDirectory() throws IOException {
    File resolverOutputDir = tempDir.resolve("resolver-output").toFile();

    JavaResolversCodegen.Result result =
        codegen.generate(
            List.of(schemaFile.toFile()),
            resolverOutputDir,
            "com.example.grt",
            "com.example.tenant");

    // Verify counts - 2 resolver files (Query, User), 2 resolvers total
    assertEquals(2, result.resolverFileCount());
    assertEquals(2, result.resolverCount());
    assertEquals(2, result.generatedFiles().size());

    // Verify files were created on disk
    Path resolverPackageDir =
        resolverOutputDir.toPath().resolve("com/example/tenant/resolverbases");
    assertTrue(Files.exists(resolverPackageDir.resolve("QueryResolvers.java")));
    assertTrue(Files.exists(resolverPackageDir.resolve("UserResolvers.java")));

    // Verify file contents
    String queryResolverContent =
        Files.readString(resolverPackageDir.resolve("QueryResolvers.java"));
    assertTrue(queryResolverContent.contains("package com.example.tenant.resolverbases;"));
    assertTrue(queryResolverContent.contains("import com.example.grt.*;"));
    assertFalse(queryResolverContent.contains("import com.example.tenant.*;"));
    assertTrue(queryResolverContent.contains("public final class QueryResolvers"));
    assertTrue(
        queryResolverContent.contains(
            "@ResolverFor(typeName = \"Query\", fieldName = \"user\", isSelective = false,"
                + " isBatching = false)"));

    String userResolverContent = Files.readString(resolverPackageDir.resolve("UserResolvers.java"));
    assertTrue(userResolverContent.contains("package com.example.tenant.resolverbases;"));
    assertTrue(userResolverContent.contains("public final class UserResolvers"));
    assertTrue(
        userResolverContent.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"profile\", isSelective = true,"
                + " isBatching = false)"));
    assertTrue(userResolverContent.contains("public Object getSelections()"));
  }

  @Test
  void createsOutputDirectoryIfNotExists() throws IOException {
    File resolverOutputDir = tempDir.resolve("nested/resolver/dir").toFile();
    assertFalse(resolverOutputDir.exists());

    codegen.generate(
        List.of(schemaFile.toFile()), resolverOutputDir, "com.example.grt", "com.example.tenant");

    assertTrue(resolverOutputDir.exists());
  }

  @Test
  void generatedFilesContainAbsolutePaths() throws IOException {
    File resolverOutputDir = tempDir.resolve("resolver-output").toFile();

    JavaResolversCodegen.Result result =
        codegen.generate(
            List.of(schemaFile.toFile()),
            resolverOutputDir,
            "com.example.grt",
            "com.example.tenant");

    for (File file : result.generatedFiles()) {
      assertTrue(file.isAbsolute());
      assertTrue(file.exists());
    }
  }

  @Test
  void returnsEmptyResultWhenNoResolverFields() throws IOException {
    // Create a schema without resolver fields
    String schemaWithoutResolvers =
        """
        extend type Query {
          hello: String
        }

        type User {
          id: ID!
          name: String!
        }
        """;

    Path noResolverSchemaFile =
        writeSchemaWithDefaults("no-resolvers.graphqls", schemaWithoutResolvers);

    File resolverOutputDir = tempDir.resolve("resolver-output").toFile();

    JavaResolversCodegen.Result result =
        codegen.generate(
            List.of(noResolverSchemaFile.toFile()),
            resolverOutputDir,
            "com.example.grt",
            "com.example.tenant");

    assertEquals(0, result.resolverFileCount());
    assertEquals(0, result.resolverCount());
    assertTrue(result.generatedFiles().isEmpty());
  }

  private Path writeSchemaWithDefaults(String fileName, String schema) throws IOException {
    var registry = new SchemaParser().parse(schema);
    DefaultSchemaFactory.INSTANCE.addDefaults(
        registry,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        false,
        false,
        false);

    Path path = tempDir.resolve(fileName);
    Files.writeString(
        path,
        TypeDefinitionRegistryExtensionsKt.toSDL(
            registry, Predicates.INSTANCE.alwaysTrue(), Predicates.INSTANCE.alwaysTrue()));
    return path;
  }
}
