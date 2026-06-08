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

### 2. Tests inside the "gradle test apps" build

The included build under `gradle-plugins/gradletestapps/` contains small Viaduct test applications used to test the kind of task executions we do not want to test with TestKit. There are three test applications there:

- `:one-project` - a single Gradle project containing both the application and module plugins
- `:two-project` - an application project with a nested `:two-project:resolvers` module project, focused on minimal cross-project runtime execution
- `:multi-project` - an application project with two nested module projects, `:multi-project:alpha` and `:multi-project:beta`

These fixtures exercise task-execution paths of the plugins, including codegen execution, schema assembly behavior, and direct end-to-end execution against a real `Viaduct` instance. The `:multi-project` fixture is where we keep the richer cross-project generated-output assertions.

These fixtures are intended to run only as part of the OSS composite build. Standalone published-artifact coverage belongs to the demoapps instead.

Note that the demoapps represent an additional source of task-execution testing. The gradle test apps here are a deliberately designed test suite; the demoapps supplement that with less surgical, real-worldish test cases.
