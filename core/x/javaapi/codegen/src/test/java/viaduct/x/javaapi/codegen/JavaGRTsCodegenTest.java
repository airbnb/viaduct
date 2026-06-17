package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertEquals(1, result.enumCount());
    assertEquals(1, result.objectCount());
    assertEquals(1, result.inputCount());
    assertEquals(1, result.interfaceCount());
    assertEquals(1, result.unionCount());
    assertEquals(5, result.totalCount());

    // Verify generated files list
    assertEquals(5, result.generatedFiles().size());

    // Verify files were created on disk
    Path packageDir = grtOutputDir.toPath().resolve("com/example/generated");
    assertTrue(Files.exists(packageDir.resolve("BookingStatus.java")));
    assertTrue(Files.exists(packageDir.resolve("User.java")));
    assertTrue(Files.exists(packageDir.resolve("CreateUserInput.java")));
    assertTrue(Files.exists(packageDir.resolve("Node.java")));
    assertTrue(Files.exists(packageDir.resolve("SearchResult.java")));

    // Verify file contents
    String enumContent = Files.readString(packageDir.resolve("BookingStatus.java"));
    assertTrue(enumContent.contains("package com.example.generated;"));
    assertTrue(enumContent.contains("public enum BookingStatus"));

    String objectContent = Files.readString(packageDir.resolve("User.java"));
    assertTrue(objectContent.contains("package com.example.generated;"));
    assertTrue(objectContent.contains("public class User"));

    String inputContent = Files.readString(packageDir.resolve("CreateUserInput.java"));
    assertTrue(inputContent.contains("package com.example.generated;"));
    assertTrue(inputContent.contains("public class CreateUserInput"));

    String interfaceContent = Files.readString(packageDir.resolve("Node.java"));
    assertTrue(interfaceContent.contains("package com.example.generated;"));
    assertTrue(interfaceContent.contains("public interface Node"));

    String unionContent = Files.readString(packageDir.resolve("SearchResult.java"));
    assertTrue(unionContent.contains("package com.example.generated;"));
    assertTrue(unionContent.contains("public interface SearchResult"));
  }

  @Test
  void createsOutputDirectoryIfNotExists() throws IOException {
    File grtOutputDir = tempDir.resolve("nested/grt/dir").toFile();
    assertFalse(grtOutputDir.exists());

    codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example", false);

    assertTrue(grtOutputDir.exists());
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
    assertEquals(4, result.objectCount());

    Path packageDir = grtOutputDir.toPath().resolve("com/example/root");
    assertTrue(Files.exists(packageDir.resolve("Query.java")));
    assertTrue(Files.exists(packageDir.resolve("Mutation.java")));
    assertTrue(Files.exists(packageDir.resolve("Subscription.java")));
    assertTrue(Files.exists(packageDir.resolve("User.java")));

    // Root types should use marker interfaces
    String queryContent = Files.readString(packageDir.resolve("Query.java"));
    assertTrue(queryContent.contains("extends ObjectBase"));
    assertTrue(queryContent.contains("implements viaduct.java.api.types.Query"));

    String mutationContent = Files.readString(packageDir.resolve("Mutation.java"));
    assertTrue(mutationContent.contains("extends ObjectBase"));
    assertTrue(mutationContent.contains("implements viaduct.java.api.types.Mutation"));

    // Regular types should extend ObjectBase (GraphQLObject is inherited)
    String userContent = Files.readString(packageDir.resolve("User.java"));
    assertTrue(userContent.contains("extends ObjectBase"));
    assertFalse(userContent.contains("implements GraphQLObject"));
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
    assertEquals(1, result.objectCount());

    Path packageDir = grtOutputDir.toPath().resolve("com/example/exclude");
    assertFalse(Files.exists(packageDir.resolve("Query.java")));
    assertTrue(Files.exists(packageDir.resolve("User.java")));
  }

  @Test
  void generatedFilesContainAbsolutePaths() throws IOException {
    File grtOutputDir = tempDir.resolve("grt-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(List.of(schemaFile.toFile()), grtOutputDir, "com.example", false);

    for (File file : result.generatedFiles()) {
      assertTrue(file.isAbsolute());
      assertTrue(file.exists());
    }
  }
}
