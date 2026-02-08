package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        directive @resolver on FIELD_DEFINITION

        type Query {
          user(id: ID!): User @resolver
        }

        type User {
          id: ID!
          name: String!
          profile: Profile @resolver
        }

        type Profile {
          bio: String
        }
        """;

    schemaFile = tempDir.resolve("schema.graphqls");
    Files.writeString(schemaFile, schema);
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
    assertThat(result.resolverFileCount()).isEqualTo(2);
    assertThat(result.resolverCount()).isEqualTo(2);
    assertThat(result.generatedFiles()).hasSize(2);

    // Verify files were created on disk
    Path resolverPackageDir =
        resolverOutputDir.toPath().resolve("com/example/tenant/resolverbases");
    assertThat(resolverPackageDir.resolve("QueryResolvers.java")).exists();
    assertThat(resolverPackageDir.resolve("UserResolvers.java")).exists();

    // Verify file contents
    String queryResolverContent =
        Files.readString(resolverPackageDir.resolve("QueryResolvers.java"));
    assertThat(queryResolverContent)
        .contains("package com.example.tenant.resolverbases;")
        .contains("public final class QueryResolvers")
        .contains("@ResolverFor(typeName = \"Query\", fieldName = \"user\")");

    String userResolverContent = Files.readString(resolverPackageDir.resolve("UserResolvers.java"));
    assertThat(userResolverContent)
        .contains("package com.example.tenant.resolverbases;")
        .contains("public final class UserResolvers")
        .contains("@ResolverFor(typeName = \"User\", fieldName = \"profile\")");
  }

  @Test
  void createsOutputDirectoryIfNotExists() throws IOException {
    File resolverOutputDir = tempDir.resolve("nested/resolver/dir").toFile();
    assertThat(resolverOutputDir).doesNotExist();

    codegen.generate(
        List.of(schemaFile.toFile()), resolverOutputDir, "com.example.grt", "com.example.tenant");

    assertThat(resolverOutputDir).exists();
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
      assertThat(file.isAbsolute()).isTrue();
      assertThat(file).exists();
    }
  }

  @Test
  void returnsEmptyResultWhenNoResolverFields() throws IOException {
    // Create a schema without resolver fields
    String schemaWithoutResolvers =
        """
        type Query {
          hello: String
        }

        type User {
          id: ID!
          name: String!
        }
        """;

    Path noResolverSchemaFile = tempDir.resolve("no-resolvers.graphqls");
    Files.writeString(noResolverSchemaFile, schemaWithoutResolvers);

    File resolverOutputDir = tempDir.resolve("resolver-output").toFile();

    JavaResolversCodegen.Result result =
        codegen.generate(
            List.of(noResolverSchemaFile.toFile()),
            resolverOutputDir,
            "com.example.grt",
            "com.example.tenant");

    assertThat(result.resolverFileCount()).isEqualTo(0);
    assertThat(result.resolverCount()).isEqualTo(0);
    assertThat(result.generatedFiles()).isEmpty();
  }
}
