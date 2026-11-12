# Resolution animations

Generate a step-through animation of a concrete Runtime2 resolution from a resolver contract test. The viewer shows a two-rail task track, a growing Object Engine Result (OER) tree or construction-demand view, and an explanation of each step. It uses recorded production observations; animation timing is illustrative.

Any future additions or modifications to the animations should be guided by [animation-design.md](animation-design.md).

## Supported resolution features

The animator currently supports simple queries, including passive values, polymorphic object results, resolvers that use object fragments or Query fragments, and singular root-field references including Node references. Fixture fields use `@resolver(script: ...)`, Node lookups use `@nodeResolver(ids: ..., script: ...)`, and scripted references use `ref(...)`; these declarations are retained in the displayed schema. Reference chains retain one publication task and destination cell across multiple target invocations; targets can prepare independent Query inputs. Node chains show the first Node identity retained for demanded output. References supplied inside returned objects also work. The demand view does not yet display type conditions explicitly. Use cases involving checkers, general variable definitions, `@parent` fields, lists (including list-element references), scripted `query(...)` subqueries, `@skip`/`@include`, mutations, or error handling are not yet properly animated.

## Generate and view

Use Python 3 and the normal Runtime2 Gradle prerequisites. No Node.js, npm install, or browser framework is required. Run the following commands from the Viaduct open-source root (`projects/viaduct/oss` in Treehouse).

For the complete supported debugger walkthrough, run one command:

```sh
./gradlew :core:engine:runtime2:easteregg \
  --no-configuration-cache --console=plain
```

This records nine production resolver contract examples in one test execution: selective output, transitive sibling demand, descendant demand, recursive object-fragment demand, transitive Query fragments, shared Query-owner projections, selective Query successor demand, a Node/root-field reference chain, and a passive reference chain with independent Query input. It builds all nine animations before starting the gallery server at [http://localhost:8765/](http://localhost:8765/). Existing animations remain in the gallery.

The task prints viewing instructions, including an SSH port-forwarding command for remote workstations. Add `-PanimationPort=8080` to use another port; the printed URL and SSH command both use that port. The server listens only on the loopback interface. Press Ctrl-C in the Gradle terminal to stop it.

To generate a single example with object-fragment dependencies and nested passive resolution:

```sh
./gradlew :core:engine:runtime2:animate \
  '-PanimationTest=viaduct.engine.runtime2.resolution.ResolverContractTest.resolves descendant demand before its consuming sibling' \
  --no-configuration-cache --console=plain
```

In another terminal, serve the generated gallery:

```sh
./gradlew :core:engine:runtime2:animationViewer \
  --no-configuration-cache --console=plain
```

Open [http://localhost:8765/](http://localhost:8765/). Stop the viewer with Ctrl-C. To use a different port, add `-PanimationPort=8080` to the viewer command. The server binds to the local machine and keeps running until stopped.

The gallery lists the most recently generated animations first and refreshes every five seconds. Generating another animation while the server runs adds its link to the gallery. Generating the same test replaces that test's animation; this is a build artifact, not a history of executions. Reload an already open animation page after regenerating it.

For a smaller first example, omit `-PanimationTest`: the default is `ResolverContractTest.omits unselected passive fields from selective resolver outputs`. Another dependency example is `viaduct.engine.runtime2.resolution.ResolverContractTest.closes and orders transitive sibling resolver demand`.

## Choose a test

`-PanimationTest` takes an exact fully qualified `class.method`, including spaces in Kotlin test method names. Quote the entire argument. Wildcards are rejected. Choose an ordinary JUnit `@Test` that calls `ResolverContract.observeResolution` once and succeeds without checkers. Use the production class in `viaduct.engine.runtime2.resolution` when illustrating the production algorithm; other resolver families can produce different task protocols.

Dynamic and parameterized tests are not recorded. Calls with `record = false` opt out; tests with multiple resolutions must opt out rather than assign call ordinals. Recording is currently integrated through the resolver contract fixture, so arbitrary Engine API tests do not automatically produce an animation.

`animate` runs the selected test through `recordAnimation`, which enables recording automatically, then renders its JSON log. Gradle may reuse the existing test execution when that task is up-to-date; the Python rendering still runs. Recordings are intentionally not declared as Gradle task outputs. If an artifact has been deleted, or you want a fresh execution, add `--rerun-tasks` to the generation command.

`easteregg` follows the same policy through `recordEasteregg` and `animateEasteregg`: the nine-test recording task can be up-to-date, while rendering runs again before the server starts. Changing `animationPort` alone does not require another test execution. `animationTest` selects the single-example `animate` workflow; it does not change the nine examples selected by `easteregg`.

For the Node reference chain alone, use `-PanimationTest=viaduct.engine.runtime2.resolution.ResolverContractTest.chases node and root-field references in one publication` with `animate`. The complete DSL setup appears on the page: `viewer` returns the Node reference `User: alias`, its node resolver returns a reference to `redirect`, and `redirect` returns the Node reference `User: canonical`. The final data comes from `canonical`, while the demanded `id` remains `alias`. For a reference supplied in an object and a target with Query dependencies, select `viaduct.engine.runtime2.resolution.ResolverContractTest.chases a passive reference through an independent Query input`.

## Viewer controls and notation

- Each page begins with the fixture's schema and query. Schema text retains resolver-test DSL declarations where the fixture uses them.
- Next and Auto play transitions. Prev, Reset, clicking a timeline mark, and scrubbing jump directly to the chosen state and pause. Arrow keys also navigate.
- Timeline labels mark every fifth step; hovering over any mark shows its step number and title.
- Purple means **Executing**. Pale yellow means **Executing passive field resolution**. Finished tasks are unfilled with a green checkmark. An orchestrator can be highlighted while its creating field task remains yellow during passive resolution.
- Task-track labels include the creator in parentheses: `#5 (#4) - rendered`. Other references use the task number. Lines connect orchestrators to their field tasks.
- Task cards stay at a fixed size. The track scrolls horizontally and brings the active task into view.
- Cells progress from `reserved` to `claimed by #N` to `completed: VALUE`. Passive values can complete a reserved cell without a field-task claim.
- Reference captions grow a chain of targets as one field task follows them. Target invocation and the subsequent tail check are separate steps; a hop does not allocate a new publication task or publish an intermediate reference. Fresh invocation roots are identity scopes, so they are not displayed as empty result trees.
- Switch the left pane between **OER** and **Construction demand** without changing position. Client demand is bold; added demand is slightly gray. Required input fields are highlighted at resolver invocation.
- The panes scroll as their contents grow and focus on changes. Drag the handle underneath to resize their height. The player respects reduced-motion preferences.
- Small Octocat icon buttons next to individual phrases open the corresponding source file with relevant lines highlighted. The modal has line numbers and scrolls in both directions. Click a line number, then Shift-click another to highlight a range; **Reset highlights** restores the suggested ranges. Opening source pauses playback. Close with **Close**, Escape, or a click outside the modal.

Source files are copied from the current worktree when an animation is generated, including uncommitted edits. Regenerate the animation after editing resolution code to update its source display. Existing generated pages gain code pointers when regenerated; viewing requires no source-server endpoint or network access.

The ordinary explanations describe resolution. The expandable **Recorded facts** and **Recording and sources** sections expose the underlying JSON for debugging. These animations show construction before GraphQL response completion.

## Artifacts and renderer-only iteration

Paths below are relative to Runtime2's Gradle `layout.buildDirectory`: `resolution/recordings/<test-class>/<method>-<hash>.json` holds the log, and `animations/<method>-<hash>/index.html` holds the animation with a copy of `recording.json`. The gallery is `animations/index.html`, with shared browser files in `animations/assets`.

In a conventional checkout this is `core/engine/runtime2/build`. Treehouse redirects Gradle outputs beneath the worktree's `dist/gradle/viaduct-oss/core/build/engine/runtime2`; use the actual path printed by `animate`. Generated files are disposable and should not be committed.

To iterate on HTML/CSS/JavaScript without rerunning the test, invoke the renderer directly with the actual artifact paths. For a conventional build directory:

```sh
python3 core/engine/runtime2/impldocs/animation/animate.py \
  --recordings core/engine/runtime2/build/resolution/recordings \
  --test 'viaduct.engine.runtime2.resolution.ResolverContractTest.resolves descendant demand before its consuming sibling' \
  --output core/engine/runtime2/build/animations
```

You can also record eligible ordinary test runs without rendering, using `./gradlew :core:engine:runtime2:test -PresolutionRecording=true --tests 'class.method' --no-configuration-cache --console=plain`. This flag uses the same recording directory; it does not enable recording in unrelated fixtures.

## Troubleshooting

- **No recording:** check that the selected test uses the contract fixture, is an ordinary `@Test`, and has not opted out. If Gradle reused a test whose log was deleted, regenerate with `--rerun-tasks`.
- **Unsupported recording:** the first viewer accepts successful, nonempty recordings with orchestration and field-resolver tasks, without checker events. Mutation-task lifecycles and checker animations are outside its current scope.
- **Construction demand disabled:** regenerate an older recording with the current recorder to include initial and closed demand.
- **Schema and query missing:** regenerate an older recording with the current recorder. Source text is supplied by the contract helpers that take a `TestWorld` and query document; calls using only decoded selections can omit it.
- **Cannot connect:** run `animationViewer` in a terminal that remains open, check its printed port, and use the same machine's browser. Generation alone does not start a server.

The visual conventions and player originate in [Alex's Runtime2 tour](https://pages.git.musta.ch/alex-ubillus/viaduct-runtime2/); see the [design and code tour](animation-design.md) for source attribution, implementation boundaries, and validation guidance.
