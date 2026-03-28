## Testing Strategy

Testing for the Viaduct Gradle plugins is split across two layers.

### 1. Tests inside of the Gradle plugins project

Use plain unit tests, `ProjectBuilder` and `TestKit` for things like:

- schema validation and other complex logic (plain unit tests)
- task and plugin model wiring (ProjectBuilder)
- clear diagnostics for invalid or incomplete plugin configuration (TestKit)

These tests should stay narrow and deterministic. In particular in-project tests are **not** the place to prove end-to-end task execution or other dependency wiring that is fragile when reproduced in TestKit. TestKit testing should focus on tests like:

- clear diagnostics for invalid or incomplete plugin configuration (as mentioned)
- configuration-time enforcement of plugin contracts
- confirming that correctly configured projects do not fail during configuration
- shallow smoke checks that expected tasks or configurations are wired into the model

### 2. Tests inside the "execution tests" included builds

Included builds under `execution-tests/` contain small Viaduct projects used to test the kind of task executions we do not want to test with TestKit.  There are two builds there:

- `one-project` - a single Gradle project containing both the application and module plugins
- `two-project` - two Gradle projects separating the application and module plugins

These builds exercise task-execution paths of the plugins, including codegen execution and `serve` behavior.

Note that the demoapps represent an additional source of task-execution testing.  The execution tests here are a deliberately designed test suite; the demoapps supplement that with less surgical, real-worldish test cases.
