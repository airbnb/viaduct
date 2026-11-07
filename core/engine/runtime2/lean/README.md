# Runtime2 Lean Model

This standalone Lean 4 project models Runtime2's simple, input, and output data domains, with supporting finite maps and ordering proofs. It currently establishes the data foundation for resolution modeling; it does not yet model or verify the field-resolution algorithm. Its build is independent of Runtime2's JVM build.

## Install Tooling

Install [elan](https://github.com/leanprover/elan), Lean's toolchain manager. It provides the `lean` compiler and the `lake` build tool and selects the version pinned in [`lean-toolchain`](lean-toolchain).

On macOS or Linux, run these commands in a terminal with `curl` available:

```sh
curl --proto '=https' --tlsv1.2 -sSf https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh |
  sh -s -- -y --default-toolchain none
. "$HOME/.elan/env"
```

The installer configures your shell's search path; sourcing the environment file makes the commands available in the current terminal. `--default-toolchain none` leaves toolchain selection to each project's configuration.

For Windows, follow [elan's installation instructions](https://github.com/leanprover/elan#installation), then run the build commands below from your checkout.

## Build and Check

The **OSS root** is the repository root of [airbnb/viaduct](https://github.com/airbnb/viaduct), or `projects/viaduct/oss` within Treehouse. From that directory:

```sh
cd core/engine/runtime2/lean
lake build
```

On the first invocation, elan downloads the pinned Lean toolchain. This project uses Lean's bundled standard library and has no external Lean package dependencies.

`lake build` compiles the definitions, checks the proofs, and runs the compile-time tests configured as default targets in [`lakefile.toml`](lakefile.toml): `Defs.MapTest`, `Model.SimpleDataTest`, `Model.InputDataTest`, and `Model.OutputDataTest`. Those tests include executable assertions and checks that invalid constructions are rejected. Any failed proof or test makes the build fail. Later builds reuse unchanged outputs under `.lake/`.

To inspect the selected tool versions, run these commands from the same directory:

```sh
lean --version
lake --version
```

The current definitions live under [`Defs`](Defs) and [`Model`](Model). Documents under [`impldocs/old`](impldocs/old) are historical notes rather than current design guidance.
