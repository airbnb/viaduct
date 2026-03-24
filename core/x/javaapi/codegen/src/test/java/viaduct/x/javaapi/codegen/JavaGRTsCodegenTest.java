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

/** Tests for JavaGRTsCodegen end-to-end code generation. */
class JavaGRTsCodegenTest {

  @TempDir Path tempDir;

  private Path schemaFile;
  private JavaGRTsCodegen codegen;

  @BeforeEach
  void setUp() throws IOException {
    codegen = new JavaGRTsCodegen();

    // Create a test schema file with various GraphQL types
    String schema =
        """
        enum BookingStatus {
          PENDING
          CONFIRMED
          CANCELLED
        }

        type User {
          id: ID!
          name: String!
          email: String
        }

        input CreateUserInput {
          name: String!
          email: String
        }

        interface Node {
          id: ID!
        }

        union SearchResult = User
        """;

    schemaFile = tempDir.resolve("schema.graphqls");
    Files.writeString(schemaFile, schema);
  }

  @Test
  void generatesAllTypesToFiles() throws IOException {
    File grtOutputDir = tempDir.resolve("grt-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(
            List.of(schemaFile.toFile()), grtOutputDir, "com.example.generated", false);

    // Verify counts
    assertThat(result.enumCount()).isEqualTo(1);
    assertThat(result.objectCount()).isEqualTo(1);
    assertThat(result.inputCount()).isEqualTo(1);
    assertThat(result.interfaceCount()).isEqualTo(1);
    assertThat(result.unionCount()).isEqualTo(1);
    assertThat(result.totalCount()).isEqualTo(5);

    // Verify generated files list
    assertThat(result.generatedFiles()).hasSize(5);

    // Verify files were created on disk
    Path packageDir = grtOutputDir.toPath().resolve("com/example/generated");
    assertThat(packageDir.resolve("BookingStatus.java")).exists();
    assertThat(packageDir.resolve("User.java")).exists();
    assertThat(packageDir.resolve("CreateUserInput.java")).exists();
    assertThat(packageDir.resolve("Node.java")).exists();
    assertThat(packageDir.resolve("SearchResult.java")).exists();

    // Verify file contents
    String enumContent = Files.readString(packageDir.resolve("BookingStatus.java"));
    assertThat(enumContent)
        .contains("package com.example.generated;")
        .contains("public enum BookingStatus");

    String objectContent = Files.readString(packageDir.resolve("User.java"));
    assertThat(objectContent)
        .contains("package com.example.generated;")
        .contains("public class User");

    String inputContent = Files.readString(packageDir.resolve("CreateUserInput.java"));
    assertThat(inputContent)
        .contains("package com.example.generated;")
        .contains("public class CreateUserInput");

    String interfaceContent = Files.readString(packageDir.resolve("Node.java"));
    assertThat(interfaceContent)
        .contains("package com.example.generated;")
        .contains("public interface Node");

    String unionContent = Files.readString(packageDir.resolve("SearchResult.java"));
    assertThat(unionContent)
        .contains("package com.example.generated;")
        .contains("public interface SearchResult");
  }

  @Test
  void createsOutputDirectoryIfNotExists() throws IOException {
    File grtOutputDir = tempDir.resolve("nested/grt/dir").toFile();
    assertThat(grtOutputDir).doesNotExist();

    codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example", false);

    assertThat(grtOutputDir).exists();
  }

  @Test
  void includeRootTypesGeneratesQueryMutationSubscription() throws IOException {
    // Schema with root types
    String schemaWithRootTypes =
        """
        type Query {
          hello: String
        }

        type Mutation {
          doSomething: String
        }

        type Subscription {
          onEvent: String
        }

        type User {
          name: String
        }
        """;

    Path rootSchemaFile = tempDir.resolve("root-schema.graphqls");
    Files.writeString(rootSchemaFile, schemaWithRootTypes);
    File grtOutputDir = tempDir.resolve("root-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(List.of(rootSchemaFile.toFile()), grtOutputDir, "com.example.root", true);

    // All 4 object types should be generated (3 root + 1 regular)
    assertThat(result.objectCount()).isEqualTo(4);

    Path packageDir = grtOutputDir.toPath().resolve("com/example/root");
    assertThat(packageDir.resolve("Query.java")).exists();
    assertThat(packageDir.resolve("Mutation.java")).exists();
    assertThat(packageDir.resolve("Subscription.java")).exists();
    assertThat(packageDir.resolve("User.java")).exists();

    // Root types should use marker interfaces
    String queryContent = Files.readString(packageDir.resolve("Query.java"));
    assertThat(queryContent).contains("implements viaduct.java.api.types.Query");

    String mutationContent = Files.readString(packageDir.resolve("Mutation.java"));
    assertThat(mutationContent).contains("implements viaduct.java.api.types.Mutation");

    // Regular types should use GraphQLObject
    String userContent = Files.readString(packageDir.resolve("User.java"));
    assertThat(userContent).contains("implements GraphQLObject");
  }

  @Test
  void excludeRootTypesSkipsQueryMutationSubscription() throws IOException {
    String schemaWithRootTypes =
        """
        type Query {
          hello: String
        }

        type User {
          name: String
        }
        """;

    Path rootSchemaFile = tempDir.resolve("exclude-schema.graphqls");
    Files.writeString(rootSchemaFile, schemaWithRootTypes);
    File grtOutputDir = tempDir.resolve("exclude-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(
            List.of(rootSchemaFile.toFile()), grtOutputDir, "com.example.exclude", false);

    // Only User should be generated, Query should be excluded
    assertThat(result.objectCount()).isEqualTo(1);

    Path packageDir = grtOutputDir.toPath().resolve("com/example/exclude");
    assertThat(packageDir.resolve("Query.java")).doesNotExist();
    assertThat(packageDir.resolve("User.java")).exists();
  }

  @Test
  void generatedFilesContainAbsolutePaths() throws IOException {
    File grtOutputDir = tempDir.resolve("grt-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example", false);

    for (File file : result.generatedFiles()) {
      assertThat(file.isAbsolute()).isTrue();
      assertThat(file).exists();
    }
  }
}
