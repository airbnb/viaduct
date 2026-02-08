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
        codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example.generated");

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

    codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example");

    assertThat(grtOutputDir).exists();
  }

  @Test
  void generatedFilesContainAbsolutePaths() throws IOException {
    File grtOutputDir = tempDir.resolve("grt-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example");

    for (File file : result.generatedFiles()) {
      assertThat(file.isAbsolute()).isTrue();
      assertThat(file).exists();
    }
  }
}
