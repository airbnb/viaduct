# Resolution animation design and code tour

This document guides changes to the recorded-resolution animations. Start with the [usage guide](README.md) to generate an example. For algorithm semantics, use the maintained [resolution architecture](../architecture/resolution.md) and the production code linked below; keep this viewer faithful to both as they evolve.

## Purpose and boundaries

The animation teaches how tasks construct one result tree during a concrete execution. It combines a task track, evolving OER or construction-demand view, and a description of the work currently on stage. It is an educational presentation of observations already useful for resolution validation. Keep presentation code here and recording support in test fixtures; avoid adding production instrumentation just to obtain finer animation timing.

The recording is general resolution data in `viaduct.engine.runtime2.resolution.recording`, deliberately independent of animation. The observer decorates the existing validation observer. Production retains one `ResolutionObserver` interface with nullable callbacks, so unsubscribed hooks skip argument construction and observation-only work.

The first viewer accepts successful, nonempty recordings with orchestration and field-resolver tasks and no checker events. It does not teach checker protocols, general mutation-task lifecycles, or GraphQL response completion. It is not a wall-clock trace: recorded event order supplies the backbone, related callbacks may be grouped, and substep times are illustrative. Highlighting identifies the task on stage, not the set of coroutines executing simultaneously. Do not imply independently observed timings for successor demand, inclusion, arguments, input reads, resolver return, or passive descent when the log only bounds those activities.

## Code tour: test to page

### Gradle entry points

[`animation.gradle.kts`](animation.gradle.kts) is applied by Runtime2's [`build.gradle.kts`](../../build.gradle.kts). It resolves sources from `impldocs/animation` and outputs through `layout.buildDirectory`, so it also works when Treehouse redirects build directories.

- `recordAnimation` is a JUnit `Test` task for one exact `-PanimationTest=class.method`. It configures the recording directory and extension autodetection; it reuses the normal test classes and classpath.
- `animate` depends on that task and invokes Python to generate the selected page and refresh the gallery. Rendering runs even if recording is up-to-date.
- `animationViewer` runs Python's local HTTP server against the animation output directory. It neither records nor generates anything. The gallery's five-second refresh makes newly generated pages discoverable.
- `recordEasteregg` selects exactly nine inherited methods on the production `viaduct.engine.runtime2.resolution.ResolverContractTest` in one JUnit execution. Its fixed list is independent of `animationTest` and includes the selective Query producer and two reference-chain examples expressed in the resolver DSL.
- `animateEasteregg` depends on that recording task and invokes the same builder with nine repeated `--test` arguments. The builder renders each page and refreshes the shared gallery after all requested pages succeed.
- `easteregg` depends on `animateEasteregg`, so recording or rendering failure prevents server startup. It serves the existing gallery, including older animations, after printing the localhost URL, remote SSH forwarding command, and Ctrl-C instructions.

The `recordAnimations` and `renderAnimations` helpers share the single-example and walkthrough setup. Both recording tasks disable build-cache reuse, retain normal task-level up-to-date checks, and leave JSON artifacts undeclared as outputs; both render tasks run on each invocation. `serveAnimationGallery` shares the server setup between `animationViewer` and `easteregg`, binding to `127.0.0.1` on port 8765 or `animationPort`. Printed URLs use `localhost`, and an overridden port is reflected in both the URL and SSH command immediately before server startup.

The ordinary test-task `-PresolutionRecording=true` wiring lives in [`development.gradle.kts`](../../development.gradle.kts). Recordings are replaceable build artifacts, not declared Gradle outputs. Test execution can be conservatively reused at task granularity; missing artifacts require a forced rerun. Do not add per-observation incremental-build identities or append-only execution histories for this use case.

### Recording boundary

[`ResolverContract.observeResolution`](../../src/testFixtures/kotlin/viaduct/engine/runtime2/contract/ResolverContract.kt) is the shared entry point. `resolve` is a strategy hook, not a direct test API. With recording enabled, `observeResolution` decorates the validation observer and records the strategy's result. `record = false` exempts generated cases and tests that cannot satisfy one resolution per ordinary test method.

[`ResolutionRecordingExtension`](../../src/test/kotlin/viaduct/engine/runtime2/resolution/recording/junit/ResolutionRecordingExtension.kt) registers the ordinary JUnit method's class and name before each test and removes the session afterward. [`ResolutionRecordings`](../../src/testFixtures/kotlin/viaduct/engine/runtime2/resolution/recording/ResolutionRecordings.kt) associates that session with the test instance across coroutine threads, rejects a second recorded resolution, and atomically replaces its JSON artifact. This avoids invocation ordinals, stack inspection, and thread-local identities. The filename combines a readable sanitized method name with a hash, inside a directory named for the class.

[`RecordingResolutionObserver`](../../src/testFixtures/kotlin/viaduct/engine/runtime2/resolution/recording/RecordingResolutionObserver.kt) assigns recording-local task and OER IDs, captures creator relationships, and detaches relevant metadata into JSON facts immediately inside callbacks. Tasks and OERs are live mutable objects and callbacks can arrive concurrently: retain the single recording lock around ID assignment, event sequencing, and snapshot construction. Delegate callbacks run outside that lock. Do not defer reading live objects until file serialization or render time.

[`ResolutionRecording.kt`](../../src/testFixtures/kotlin/viaduct/engine/runtime2/resolution/recording/ResolutionRecording.kt) holds the Jackson-friendly envelope, task/OER tables, and sequenced events with detached `JsonNode` facts. Version 1 includes optional initial demand and source text; prepared-orchestration facts include closed construction demand. Task-finish facts snapshot the owning OER, and the final completion event snapshots the reachable result. Values encode OER references, lists, errors, and ordinary scalar data. IDs are stable within one recording, not across executions; concurrency can change numbering and interleaving between runs.

Root-field-reference hop facts retain the reference's canonical field path, grounded arguments, and optional decoded Node identity alongside the publication and invocation addresses. Field-task facts include `sourceReference` when the source supplied the reference directly. These optional additions keep version 1 recordings compatible and preserve general execution information, without recording resolver return values or adding production hooks. `onResolverInvocation` observes attempted entry; `onRootFieldReferenceInvocation` associates a reference helper after it returns and can also occur when input failure prevented entry. Never use a hop callback alone as proof that a resolver was invoked.

The contract helpers accepting a `TestWorld` and query document attach a `RecordedResolutionSource` to the recording session before calling `observeResolution`. `TestWorld.schemaSDL` retains the original fixture text, including resolver-test DSL declarations before they are stripped for schema construction. Keep these strings as source metadata: reconstructing them from lowered schema or demand would lose aliases, named fragments, type conditions, and externally handled fields such as `__typename`. The page displays escaped schema and query in two code panels above the animation, stacking them on narrow screens and scrolling long source text independently. Older logs remain playable with a regeneration hint when source text is absent.

### Python presentation model

[`animate.py`](animate.py) uses only the Python standard library. `build_animation` locates one exact test's log, checks identity and supported format, renders HTML, and copies browser assets and `recording.json`. The command accepts one or more repeated `--test` arguments, builds each animation, then refreshes the gallery. The generated directory combines a readable method prefix with a hash of `class.method`; regenerating that test overwrites its page. Gallery ordering uses page generation time.

The important functions are:

| Function | Responsibility |
| --- | --- |
| `subject`, `active_task`, `task_label` | Map an event to its task, distinguish the described actor from the task being created, and format references. |
| `reference_events`, `reference_label`, `reference_chain` | Associate reference invocation identities with their existing publication task, format decoded Node identities, and show the chain prefix reached at each step. |
| `animation_steps` | Add the synthetic bootstrap and group related callbacks into teaching steps, including orchestration preparation, field preparation at run entry, and orchestration-to-field handoff. |
| `phase`, `preparing_orchestrator`, `orchestration_caller` | Identify lifecycle labels and the two actors participating in preparation/run transitions. |
| `passive_resolution_spans` | Infer an object-returning field task's passive phase from its first descendant orchestration preparation through its finish. Root/path checks exclude unrelated Query work. |
| `simple_passive_steps` | Find successful invoked field tasks that complete without child object orchestration, so their closing step can illustrate passive resolution. Excludes skipped/failed cases and duplicate finishes. |
| `task_track` | Lay out the two rails, creator links, focus transfers, passive fills, and lifecycle label schedules. |
| `oer_progress` | Define the shared schedule for snapshots, cell claims, and completions. |
| `oer_frames` | Render OER snapshots with only the current frame visible and preserve previous cell content until its scheduled replacement. |
| `demand_frames` | Render the evolving union of client and closed demand, annotations, and required-input highlights, using the same cell schedule as the OER view. |
| `caption` | Write the teaching script, actor reference, invocation inputs, and optional raw facts. |
| `render`, `page`, `gallery` | Assemble each scene, version asset URLs by content hash, and build the reverse-chronological index. |

Schema and query panels come from recorded source text for every example, with no test-specific snippets. Preserve both `@resolver(result: ...)` and `@resolver(script: ...)` declarations. Script statements do not supply execution events: resolver invocation, input, and result facts still come from the existing recorder, and the player does not illustrate individual JEXL statements. Two examples retain introductory explanations in `render`: output projection and transitive sibling demand. If those tests change, update their explanations too; do not hard-code their task numbers or event sequence into the general animation machinery.

### Phrase-level source pointers

`caption` places small `code_pointer` buttons immediately after specific claims, such as computing successor demand, claiming a value promise, or reading required inputs. These associations belong to phrases, not whole panels. Buttons display the bundled GitHub Octicons mark from `assets/mark-github.svg`, retaining descriptive accessible labels and tooltips. CSS masks color the icon using the current theme. Keep pointers visually secondary and the prose readable without following them. Select primary implementation ranges from the production algorithm; multiple pointers can explain separate actions in one sentence.

`CODE_POINTERS` holds the title, Runtime2-relative file, and start/end text anchors for each pointer. `code_sources` reads the current worktree during generation, derives inclusive line ranges from those anchors, and embeds escaped full files in inert templates, once per file per page. It rejects missing or ambiguous start anchors and missing end anchors rather than silently highlighting stale line numbers. Update the anchors and check their semantic fidelity when resolution code changes. The files reflect generation time, including uncommitted edits; they are independent of the recording's age and require regeneration after code changes. This preserves the static gallery/server and avoids adding recording or production instrumentation.

`initCodePointers` in `assets/motion.js` uses delegated clicks because the player replaces caption HTML when the step changes. It pauses playback, opens a native modal dialog, clones the file template, highlights the pointer's ranges, and scrolls to the first highlight. The whole file remains available with a sticky line-number gutter and independent horizontal/vertical scrolling. Clicking a gutter number and Shift-clicking another selects a custom range; Reset restores the pointer's ranges. The dialog traps focus, supports Escape/backdrop/Close dismissal, and restores focus to the originating pointer. It lives outside the scene so source scrolling keys do not advance the animation. Styling stays in `assets/recording.css` and uses the existing theme colors.

### Browser player and visual foundation

[`assets/motion.js`](assets/motion.js) interprets declarative HTML/SVG schedules. `initScene` creates controls and coordinates playback, task focus, pane switching, autofocus, and resizing. Rendering is a function of scene time, so a direct seek reconstructs the same state as forward playback. Keep this property; adding imperative one-way mutations breaks backward navigation and scrubbing.

The renderer emits `data-in`/`data-dur` and `data-out`/`data-out-dur` for fades, `data-on` for visibility/active windows, `data-text` with optional text-fade or instant transitions for labels, and `data-add` for timed classes. SVG `data-task-focus` controls execution emphasis and `data-focus-at` schedules pane autofocus. The scene's `_scene.seek(time)` hook is useful for browser validation. `?t=SECONDS` selects a scene time for inspection; `?static` disables the interactive player.

[`assets/recording.css`](assets/recording.css) owns recording-specific layout, scrolling, resizing, demand styles, lifecycle fills, and timeline labels. [`assets/style.css`](assets/style.css) and [`assets/theme.js`](assets/theme.js) supply the shared visual foundation and theme handling. Keep the task-fill legend tied to the actual CSS colors in both themes.

The HTML conventions, vanilla JavaScript player, inline SVG, declarative beats, typography, and color vocabulary come from [Alex's Runtime2 tour](https://pages.git.musta.ch/alex-ubillus/viaduct-runtime2/), with sources at [alex-ubillus/viaduct-runtime2](https://git.musta.ch/alex-ubillus/viaduct-runtime2), commit `056e72078fe5778716b584f7f7d84a4b1cab9c65`. Preserve the source attribution in adapted files. Keep pages usable without a bundler, external browser assets, or additional framework dependencies, so they can integrate with the other educational material.

## Resolution story and choreography

### Production sequence

Read [`FieldResolverTask.kt`](../../src/main/kotlin/viaduct/engine/runtime2/resolution/FieldResolverTask.kt), [`OrchestrationTask.kt`](../../src/main/kotlin/viaduct/engine/runtime2/resolution/OrchestrationTask.kt), and [`SharedPassiveValueResolutionLogic.kt`](../../src/main/kotlin/viaduct/engine/runtime2/resolution/framework/SharedPassiveValueResolutionLogic.kt) before changing the script. The alternation is field task → object orchestration → field tasks, repeated as returned objects introduce more work. The causal parent of a recursively discovered orchestrator is the field task performing passive descent, even when another orchestrator was prepared in between.

A field task computes successor demand, evaluates inclusion, resolves arguments, reads required object/Query inputs, then invokes the resolver. An excluded field becomes inactive without invocation. Input reads may suspend; all inputs must be ready before invocation. These are distinct semantic actions, but the current log captures their facts together at invocation rather than independently timing each one. The **Start the field task** explanation should teach that order without inventing extra recorded callbacks.

After the resolver returns, passive value resolution descends into its result. For an object it allocates an OER and prepares an orchestrator, closes construction demand and binds variable declarations, recurses through available passive values, then dispatches the prepared orchestrator as recursion unwinds. Passive fields may have reserved cells that complete directly; reservation does not imply a field-task promise will be claimed.

For ordinary production object orchestration, [`CoroutineOrchestrationTaskBase.run`](../../src/main/kotlin/viaduct/engine/runtime2/resolution/CoroutineOrchestrationTaskBase.kt) prepares and dispatches field work and freezes the field sets without suspending. Dispatch starts it undispatched, so this run completes during the creating field task's passive phase. Its launched field tasks can run concurrently and outlive that phase. An orchestrator finishing does not mean its cells are complete or that its children have finished. Avoid generalizing this choreography to mutation orchestration or another resolver family without inspecting its protocol.

### Root-field and Node reference tails

[`FieldResolutionLogic.publishResult`](../../src/main/kotlin/viaduct/engine/runtime2/resolution/FieldResolutionLogic.kt) keeps one publication task and cell while repeatedly following `RootFieldReferenceData`. A reference can be returned by the original resolver or supplied directly in an object; the latter bypasses the containing field's registered resolver. Each hop allocates a fresh Query invocation root and resolver occurrence, declares target bindings, prepares any independent Query fragment, and invokes the target with grounded reference arguments and the publication's successor demand. Namespace prefixes locate the target; their resolvers are not executed to traverse the path. Only the eventual non-reference value enters passive resolution and completes the original cell. The first decoded Node identity remains authoritative across later hops and is restored in demanded output before passive descent.

The renderer joins hop observations to field tasks by exact publication root and path, then associates pre-entry invocation and independent-Query events by resolver occurrence ID. This uses the completed recording to explain events in their original order; it does not move the after-helper callback before invocation. Each invocation receives a **Follow a reference** step and each hop callback a **Check the reference tail** step. The caption's chain displays only the reached prefix. Its current target is teaching context, not an observed resolver return value. Do not invent a reference-discovery timestamp, show intermediate references as completed OER cells, or give every hop a new task card.

Invocation roots are identity scopes without published fields and stay out of the OER/demand panes. A nonempty independent Query input has its own orchestrator and appears in both panes, with required selections highlighted at the target invocation. Its preparation happens before the target runs; it must not start the publication task's yellow passive phase or use the ordinary passive-to-orchestrator handoff. Root/path identity separates this input work from descent into the eventual output object. Caption pointers distinguish ordinary shared Query input materialization from reference-target independent input preparation.

Selection labels render recorded variables as `$name`, preserving their full owner and target identities in the underlying facts used for matching. Node invocation captions display decoded type and ID, and completed ID cells display the ID value. Encoded Node arguments and scalar wrappers remain in the optional facts rather than obscuring the execution story.

### Preparation and handoff

The first displayed step is **Bootstrap resolution**: the root orchestrator is outlined, without a state label, and the root Query OER has no cells. This is a presentation-only initial state before the preparation snapshots. On the next transition the root becomes filled immediately with `preparing`; reserved cells appear halfway through and the label becomes `prepared` at the endpoint.

For later object preparation, introduce the new empty OER and outlined orchestrator in `preparing`. Transfer focus from the creating field task to the orchestrator, add reserved cells at the midpoint, mark it `prepared`, and transfer focus back. The explanation is that the returning field task allocates the OER and creates/prepares the orchestrator, which closes demand and binds variable declarations. Do not create a separate visually empty “prepared” step solely to mirror a callback.

At orchestration run entry, coordinate the two actors: the first third belongs to the field task's passive work, the middle third transfers focus, and the final third belongs to the orchestrator's field preparation. Complete the passive cells at one sixth of the step, then claim the active cell and introduce its field task at five sixths. The new field task starts outlined/prepared; the orchestrator remains the executing actor while creating it. Grouping these facts prevents cells from appearing claimed long before their owning task exists.

### Passive-resolution fills and completion

Purple means **Executing**. Pale yellow means **Executing passive field resolution**. For an object-returning field task, yellow starts at the first descendant preparation and remains constant through the passive phase while nested orchestrators independently receive and release execution focus. This persistent yellow shows that their work is happening inside the field task's passive descent, even when it is not the described actor.

When that field task finishes, fade directly from yellow to the unfilled state, without returning through purple. Every finished task must be unfilled and have no execution glow, with a green checkmark before `finished`. Enforce this even when focus schedules or a grouped step would otherwise keep its fill active. Repeated `FINISHED` callbacks must not replay the passive interval or revive a completed task.

For a successful simple result without child orchestration, use the closing step to show the otherwise invisible passive phase: fade purple to yellow during the first third and change the label to `passive resolution`, hold yellow during the middle third, complete affected cells at the midpoint, then fade to unfilled/`✓ finished` during the last third. Update both left-pane views at the same time. This interval is an illustrative expansion of the invocation-to-finish boundary, not a separately measured passive-resolution event.

## UI and language principles

- Keep orchestration tasks on the upper rail and their field tasks below, with plain solid connecting lines and no arrowheads. Keep task cards and text at their natural size; scroll the track horizontally as it grows and center the task receiving execution focus, including handoffs during orchestration preparation. Use smooth scrolling during forward playback and immediate positioning for direct navigation or reduced motion. Do not draw field-to-orchestrator creation arrows; creator labels communicate those relationships without clutter.
- Only the task-track labels include creators: `#5 (#4) - rendered`, or `#1 (root) - Query`. Elsewhere use `#5` or `#5 - rendered`. Describe task creation plainly, for example “Orchestrator task #1 creates field-resolver task #2.”
- Show one OER snapshot at a time so the tree grows in place. Never stack successive full trees vertically. Keep the panes compact enough for the controls and task track to remain visible, with persistent scrollbar affordances, change autofocus, and a shared bottom resize handle.
- Place controls between the task track and the OER/description panes. Only Next and Auto animate. Prev, Reset, timeline clicks, and scrubbing jump directly to the selected completed state and pause; do not seek to the preceding state and play forward.
- Start with a blue timeline cursor at the first, leftmost mark. Departed marks remain blue. A destination mark turns blue only when the moving cursor arrives. Use small muted numbers every fifth step, with a hover title on every mark.
- Cells use `reserved`, `claimed by #N`, and `completed: VALUE`. A passive cell may go directly from reserved to completed. Explain reservation as a place for a demanded value, rather than suggesting every cell needs an active resolver task.
- The construction-demand view shows the union as execution discovers closure. Initial client selections are bold, closure additions regular and slightly gray but still readable. Initial selection structure may exist before its corresponding OER/cells; align annotations with actual creation, reservation, claim, and completion times.
- At resolver invocation highlight the actual required object and Query input selections, including nested paths, in both views. Preserve selection keys, arguments, and occurrence context when matching inputs; a field name alone is not sufficient identity.
- Omit an empty auxiliary Query OER silently. When it has demanded fields, include it and explain why. Keep the OER/demand toggle at the same scene time, and disable it with a regeneration hint when an older recording lacks demand metadata.
- Ordinary captions explain resolution, using phrases such as “Demand closure and variable binding declaration happen during preparation.” Avoid observation jargon such as “captured,” “snapshot,” “No cells observed yet,” or internal layout commentary. Leave an empty new OER empty. Raw event facts belong in the optional debugging disclosure.
- Respect reduced motion and preserve keyboard navigation, focus visibility, readable contrast, and light/dark themes. Finished-task fills, legend colors, and timeline state must remain correct after arbitrary seeking as well as forward playback.

## Checking fidelity when resolution changes

Documentation cleanup for a change to the production algorithm includes checking these animations, even when no animation file was edited. Regenerate a representative production contract example and compare the recorder, grouping, inferred phases, captions, and visuals with the changed code. In particular:

1. Check task creators, object allocation, preparation versus run order, and when passive recursion dispatches orchestrators. Verify that the actor highlighted by each grouped step actually performs the work described.
2. Check construction-demand closure and variable binding declarations, successor demand, inclusion, arguments, required-input readiness, and resolver invocation. Ensure input highlights still identify the right occurrences and demand paths.
3. Check reservation, claim, passive completion, active publication, freezing, and task termination. Keep the distinction between orchestration completion and child-field completion. Verify inactive/error cases before extending the viewer to teach them.
4. Check that both left-pane views use the same timing and show the current result, that passive fills have the right boundaries, and that all finished tasks are unfilled. Revisit inferred passive intervals if the callback protocol or dispatch behavior changes.
5. Update the relevant architecture/testing documentation and this design when behavior or its presentation changes. Preserve historical evidence documents as history; regenerate disposable artifacts instead of committing generated pages.

If the existing observations cannot establish a new claim, first simplify the presentation or state the limitation. Add an observation only when it is useful resolution information, keeping it nullable and in the single observer interface. A new viewer feature should not require animation-specific machinery in the production algorithm.

## Validation and further development

For renderer/player changes, use representative recordings and browser checks; the repository's [scripts and tools guidance](../../../../../../../../_infra/docs/scripts-and-tools.md) favors this approach for scripts. Python's standard-library compiler and `node --check` can catch syntax errors without adding dependencies. Temporary Playwright checks can drive `_scene.seek`, install a controlled browser clock, inspect SVG opacity and labels, count visible frames, and compare both views at substep times. Do not introduce Node/npm requirements for users merely to validate the player.

The running example in the README exercises nested descent plus required inputs: `viewer` returns a User containing `profile.raw`; `message` requires `profile { rendered }`, and `rendered` requires `raw`. In its current recording there are 19 displayed steps: field task #2 is passive from step 6 through its finish at step 12; the `rendered` and `message` field tasks exercise simple-result closing steps. These numbers describe that example, not a general protocol; prefer locating tasks and events by facts in new checks.

A useful validation set also includes output projection, transitive sibling demand, a precomputed-demand example, and Query-fragment demand. Inspect start, midpoint, endpoint, backward jumps, timeline clicks, Auto, reduced motion, theme changes, pane overflow/resizing, and every finished task. Check the bootstrap, coordinated passive-completion/claim timing, required-input highlights, and simple-result yellow interval. Generate through Gradle after moving sources or changing wiring; a renderer-only run does not exercise that integration. Run targeted Kotlin recording tests when changing the recorder, and the requested Runtime2 checks for resolution changes.

For reference support, include both `chases node and root-field references in one publication` and `chases a passive reference through an independent Query input`. Verify one claimed destination across hops, fresh invocation identities, first-Node identity preservation, the source-reference resolver override, independent Query demand and highlights, and no passive fill during target input preparation. Seek backward from completion to every hop and input preparation; the chain, task labels, input highlights, and cell ownership must reconstruct the same state. Lists and list-element publications remain outside the supported animation scope.
