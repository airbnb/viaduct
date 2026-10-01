# Upstream GraphQL Specification

This directory contains a locally maintained copy of the GraphQL specification.

Upstream repository: https://github.com/graphql/graphql-spec

Current baseline:

- Release: September 2025
- Tag: `September2025`
- Commit: `89d93ebbe05db06787646d76a696ead8de117b2b`
- Published document: https://spec.graphql.org/September2025/

`LICENSE.md` is copied verbatim from upstream. The source under `spec/` began as the upstream release, but is intentionally reduced to the `GraphQL.md` wrapper, Chapter 6 (`Section 6 -- Execution.md`), and renderer metadata. The wrapper imports only Chapter 6, references from that chapter to removed parts of the specification resolve to the published September 2025 document, and Chapter 6 contains a Viaduct-local specialization for completing an Engine Result Tree. The remaining files in this directory are Viaduct build infrastructure.

The specification source is intentionally copied into the Viaduct repository rather than referenced as a submodule. Viaduct may annotate these files locally.

When a new GraphQL specification is formally published, refresh Chapter 6 and `LICENSE.md` from the new release tag, reapply the reduced wrapper and external-reference metadata, and reconcile the Viaduct annotations. Do not update from the upstream `main` branch or working draft.

## Building

Run `./gradlew -p core/engine/runtime2/spec clean check` from the repository root to install the locked renderer and produce `core/engine/runtime2/spec/build/spec/index.html`. The specification build is intentionally opt-in and is not part of qplan's standard `check` task.
