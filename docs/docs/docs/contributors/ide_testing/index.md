---
title: IDE Testing
description: Guide to testing IDE compatibility with Viaduct code generation plugins.
---


## Guide to Testing IDE Compatibility with a Code Generation Plugin

This guide outlines a comprehensive testing procedure to ensure proper integration and functionality of the Viaduct Gradle plugins within various Integrated Development Environments (IDEs). These plugins perform extensive code generation, which substantially complicates IDE integration. The code generation process is triggered upon modification of files within the `src/main/viaduct/schema` directories of the modules that make up a Viaduct application that have the `.graphqls` extension (we call these files collectively the module's _schema partition_).

## Automated Testing with Claude Code

### Running the Test Command

Execute the following command to run automated IDE compatibility testing with appropriate permissions:

```bash
claude --settings '{
    "sandbox": {
      "enabled": true,
      "autoAllowBashIfSandboxed": true,
      "allowUnsandboxedCommands": false,
      "excludedCommands": [],
      "network": {
        "allowUnixSockets": [],
        "allowLocalBinding": false
      }
    },
    "permissions": {
      "allow": [
        "Read(./**)",
        "Edit(./**)"
      ],
      "deny": [
        "Read(../)",
        "Edit(../)",
        "WebFetch"
      ]
    }
  }'
```

### Test Prompt

Copy and paste this prompt into Claude Code to execute automated IDE testing:

```text
Execute automated IDE compatibility testing for the Viaduct GraphQL code generation plugins.

**Setup:**

- IntelliJ IDEA should be open with viaduct project loaded (not individual demo apps)
- IntelliJ MCP server should be running at `http://localhost:64342/sse`
- Playwright MCP should be available for browser testing

**Instructions:**

1. Read viaduct/docs/content/docs/contributors/ide_testing/_index.md to understand the test scenarios
2. Focus on the **starwars** demo application only
3. Create IntelliJ run configurations for:
   - QueryResolverUnitTests
   - FilmResolverUnitTests
   - Starwars Application
4. Execute all test scenarios from the guide programmatically:
   - Baseline test execution (verify all tests pass)
   - Schema modification tests (remove/restore fields, verify failures/success)
   - Field addition test (add `status` field, verify backward compatibility)
   - Application runtime test (start app, test GraphQL API with curl)
   - Browser testing (navigate to GraphiQL, verify queries work)
5. Document all results in a comprehensive markdown report

**Key Notes:**

- Use Gradle CLI for builds since "Rebuild Project" isn't exposed via MCP
- Use curl to test GraphQL API if GraphiQL UI has issues
- Don't attempt to switch compiler modes (not accessible via MCP)
- Focus on functional validation over visual feedback
- Target 80-85% test coverage

Generate a detailed test report showing what was tested, results achieved, and any limitations encountered.
```

### Prerequisites

Before running the automated tests, ensure:

- IntelliJ IDEA is open with the viaduct project loaded (not individual demo apps)
- IntelliJ MCP server is running at `http://localhost:64342/sse`
- Playwright MCP is available for browser testing

## Test Procedures

The following steps should be performed for each IDE being tested using the two demo applications: **starwars** and **cli-starter**.

Each of these tests should be performed with **both the native/standard compiler, as well the iterative compiler provided by the IDE** to ensure that all scenarios are tested.

## Demo Applications Overview

### starwars Application (<https://github.com/viaduct-dev/starwars>)

- **Main class**: `src/main/kotlin/viaduct/demoapp/starwars/Application.kt`
- **Run command**: `./gradlew bootRun`
- **Test URL**: [http://localhost:8080/graphiql](http://localhost:8080/graphiql)
- **Key schema files**:
  - `modules/starwars/src/main/viaduct/schema/Character.graphqls`
  - `modules/starships/src/main/viaduct/schema/Starship.graphqls`
  - `modules/starwars/src/main/viaduct/schema/Film.graphqls`

### cli-starter Application (<https://github.com/viaduct-dev/cli-starter>)

- **Main class**: `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`
- **Run command**: `./gradlew -q run`
- **Test queries**:
  - Default: `{ greeting }` (should return "Hello, World!")
  - Custom: `./gradlew -q run --args="'{ author }'"` (should return "Brian Kernighan")
- **Key schema file**: `src/main/viaduct/schema/schema.graphqls`

## 1\. Direct Application Execution and Iteration

This test verifies the IDE's ability to run the application directly, detect changes, and re-execute.

### For starwars Application

- **Step 1:** Open the starwars project (<https://github.com/viaduct-dev/starwars>) in the target IDE.
- **Step 2:** Locate and run the `main` function in `src/main/kotlin/viaduct/demoapp/starwars/Application.kt`. The application should start on <http://localhost:8080>.
- **Step 3:** Open [http://localhost:8080/graphiql](http://localhost:8080/graphiql) in your browser to verify the application is working. Try the basic query:

  ```graphql
  query {
    allCharacters(limit: 3) {
      name
      homeworld {
        name
      }
    }
  }
  ```

- **Step 4:** Make a change to `modules/starwars/src/main/viaduct/schema/Character.graphqls` that affects the query results. Add this new field:

  ```graphql
  """
  The character's status in the galaxy.
  """
  status: String
  ```

- **Step 5:** Use the IDE's "Rebuild Project" or equivalent command to trigger code generation.
- **Step 6:** Run the application again and test the new query in GraphiQL:

  ```graphql
  query {
    allCharacters(limit: 3) {
      name
      status
      homeworld {
        name
      }
    }
  }
  ```

  Verify that code generation runs and the new `status` field is available.

### For cli-starter Application

- **Step 1:** Open the cli-starter project (<https://github.com/viaduct-dev/cli-starter>) in the target IDE.
- **Step 2:** Locate and run the `main` function in `src/main/kotlin/com/example/viadapp/ViaductApplication.kt`. You should see JSON output with "Hello, World!".
- **Step 3:** Verify the application works by testing the GraphQL query execution. The output should be:

  ```json
  {
    "data": {
      "greeting": "Hello, World!"
    }
  }
  ```

- **Step 4:** Make a change to `src/main/viaduct/schema/schema.graphqls` that affects the query results. Add this new field:

  ```graphql
  farewell: String @resolver
  ```

- **Step 5:** Use the IDE's "Rebuild Project" or equivalent command to trigger code generation.
- **Step 6:** Run the application again with the new query to verify that code generation runs and the new field is available:

  ```bash
  ./gradlew -q run --args="'{ farewell }'"
  ```

## 2\. Test Execution and Iteration

This test focuses on the IDE's handling of unit tests, change detection, and re-execution.

### For starwars Application

#### Test Suite: QueryResolverUnitTests

- **Step 1:** Open the starwars project (<https://github.com/viaduct-dev/starwars>) in the target IDE.
- **Step 2:** Run `src/test/kotlin/viaduct/demoapp/starwars/QueryResolverUnitTests.kt`. Verify that all tests pass.
- **Step 3:** Break this test by removing the `name` field from `modules/starwars/src/main/viaduct/schema/Character.graphqls` (delete the line: `name: String`).
- **Step 4:** Use the IDE's "Rebuild Project" or equivalent command to trigger code generation.
- **Step 5:** Run the test again. The `allCharacters respects limit and maps fields` test should fail because it calls `result.getName()` which will no longer exist after code generation.

#### Test Suite: FilmResolverUnitTests

- **Step 1:** Run `src/test/kotlin/viaduct/demoapp/starwars/FilmResolverUnitTests.kt`. Verify that all tests pass.
- **Step 2:** Break this test by removing the `title` field from `modules/starwars/src/main/viaduct/schema/Film.graphqls` (delete the line containing `title: String`).
- **Step 3:** Use the IDE's "Rebuild Project" or equivalent command to trigger code generation.
- **Step 4:** Run the test again. The `FilmDisplayTitleResolver returns title` and other title-related tests should fail because they call methods like `getTitle()` which will no longer exist after code generation.

### For cli-starter Application

#### Test Suite: ViaductApplicationTest

- **Step 1:** Open the cli-starter project (<https://github.com/viaduct-dev/cli-starter>) in the target IDE.
- **Step 2:** Run `src/test/kotlin/com/example/viadapp/ViaductApplicationTest.kt`. Verify that all tests pass.
- **Step 3:** Break this test by removing the `greeting` field from `src/main/viaduct/schema/schema.graphqls` (delete the line: `greeting: String @resolver`).
- **Step 4:** Use the IDE's "Rebuild Project" or equivalent command to trigger code generation.
- **Step 5:** Run the test again. The `testMainWithNoArguments` and `testMainWithGreetingQuery` tests should fail because the greeting field will no longer exist in the generated GraphQL schema.

## 3\. Rebuild and Code Generation Verification

This test verifies that the IDE correctly recognizes and reflects changes introduced by the code generation plugin, specifically regarding code validity (errors, warnings, or successful compilation).

### For starwars Application

- **Step 1:** Open the starwars project (<https://github.com/viaduct-dev/starwars>) in the target IDE.
- **Step 2:** Introduce a compilation error by removing the `homeworld` field from `modules/starwars/src/main/viaduct/schema/Character.graphqls` (delete the line: `homeworld: Planet @resolver`). This will cause compilation errors in resolver code that references this field.
- **Step 3:** Observe that the IDE highlights compilation errors in the generated code or resolver files that depend on the homeworld field.
- **Step 4:** Initiate a "Rebuild Project" or equivalent command to trigger code generation.
- **Step 5:** Restore the field by adding back:

  ```graphql
  homeworld: Planet @resolver
  ```

- **Step 6:** Rebuild again and verify that the IDE no longer shows the compilation errors, indicating that code generation has updated the generated files correctly.

### For cli-starter Application

- **Step 1:** Open the cli-starter project (<https://github.com/viaduct-dev/cli-starter>) in the target IDE.
- **Step 2:** Introduce a compilation error by removing the `author` field from `src/main/viaduct/schema/schema.graphqls` (delete the line: `author: String @resolver`). This will cause the application to fail when trying to resolve author queries.
- **Step 3:** Observe that the IDE may highlight issues in generated code or when running queries that reference the missing field.
- **Step 4:** Initiate a "Rebuild Project" or equivalent command to trigger code generation.
- **Step 5:** Restore the field by adding back:

  ```graphql
  author: String @resolver
  ```

- **Step 6:** Rebuild again and verify that the IDE recognizes the restored field and code generation updates correctly.

## Expected Outcomes

For all tests, the expected outcome is that the code generation plugin runs as intended whenever a `.graphqls` file is modified and a relevant build or run action is performed. The application or tests should reflect the changes introduced by the code generation.

| Test Case            | Application | IDE Compiler    | Expected Application/Test Behavior                                        |
| :------------------- | :---------- | :-------------- | :------------------------------------------------------------------------ |
| Direct Execution (1) | starwars    | Iterative / JDK | GraphiQL reflects new fields, application runs                            |
| Direct Execution (1) | cli-starter | Iterative / JDK | New queries work, JSON output includes new fields                         |
| Test Execution (2)   | starwars    | Iterative / JDK | QueryResolverUnitTests and FilmResolverUnitTests fail when fields removed |
| Test Execution (2)   | cli-starter | Iterative / JDK | ViaductApplicationTest fails when greeting field removed                  |
| Rebuild (3)          | starwars    | Iterative / JDK | IDE shows/clears compilation errors as homeworld field removed/restored   |
| Rebuild (3)          | cli-starter | Iterative / JDK | IDE shows/clears compilation errors as author field removed/restored      |

## Reporting Issues

Any discrepancies or failures to meet the expected outcomes should be documented thoroughly, including:

- IDE name and version
- Operating system
- Specific test step where the issue occurred
- Error messages or unexpected behavior observed
- Relevant logs or screenshots

This guide will help ensure the robust functionality and compatibility of the new Gradle code generation plugin across various development environments.
