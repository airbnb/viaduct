package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
  void appliedScopesFilterExcludesOutOfScopeTypes() throws IOException {
    // Two object types in disjoint scopes; the @scope directive is declared inline so parsing
    // succeeds (the Java codegen parser does not auto-add it).
    String scopedSchema =
        """
        directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
        type InScope @scope(to: ["a"]) {
          id: ID!
        }
        type OutOfScope @scope(to: ["b"]) {
          id: ID!
        }
        """;

    Path scopedSchemaFile = tempDir.resolve("scoped-schema.graphqls");
    Files.writeString(scopedSchemaFile, scopedSchema);
    File grtOutputDir = tempDir.resolve("scoped-output").toFile();

    JavaGRTsCodegen.Result result =
        codegen.generate(
            List.of(scopedSchemaFile.toFile()),
            grtOutputDir,
            "com.example.scoped",
            false,
            Set.of("a"));

    // Only the in-scope type should be generated.
    assertEquals(1, result.objectCount());

    Path packageDir = grtOutputDir.toPath().resolve("com/example/scoped");
    assertTrue(Files.exists(packageDir.resolve("InScope.java")));
    assertFalse(Files.exists(packageDir.resolve("OutOfScope.java")));
  }

  @Test
  void noAppliedScopesGeneratesAllTypes() throws IOException {
    // Regression guard for the null/empty scope path: no filtering means every type is generated.
    String scopedSchema =
        """
        directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
        type InScope @scope(to: ["a"]) {
          id: ID!
        }
        type OutOfScope @scope(to: ["b"]) {
          id: ID!
        }
        """;

    Path scopedSchemaFile = tempDir.resolve("no-scopes-schema.graphqls");
    Files.writeString(scopedSchemaFile, scopedSchema);

    // Existing 4-arg overload: no scopes supplied.
    File grtOutputDir = tempDir.resolve("no-scopes-output").toFile();
    JavaGRTsCodegen.Result result =
        codegen.generate(
            List.of(scopedSchemaFile.toFile()), grtOutputDir, "com.example.noscopes", false);

    assertEquals(2, result.objectCount());
    Path packageDir = grtOutputDir.toPath().resolve("com/example/noscopes");
    assertTrue(Files.exists(packageDir.resolve("InScope.java")));
    assertTrue(Files.exists(packageDir.resolve("OutOfScope.java")));

    // 5-arg overload with an empty scope set behaves the same as no filtering.
    File emptyScopesOutputDir = tempDir.resolve("empty-scopes-output").toFile();
    JavaGRTsCodegen.Result emptyScopesResult =
        codegen.generate(
            List.of(scopedSchemaFile.toFile()),
            emptyScopesOutputDir,
            "com.example.emptyscopes",
            false,
            Set.of());

    assertEquals(2, emptyScopesResult.objectCount());
    Path emptyScopesPackageDir = emptyScopesOutputDir.toPath().resolve("com/example/emptyscopes");
    assertTrue(Files.exists(emptyScopesPackageDir.resolve("InScope.java")));
    assertTrue(Files.exists(emptyScopesPackageDir.resolve("OutOfScope.java")));
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
