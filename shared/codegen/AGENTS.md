# Viaduct Codegen Library

This library generates JVM bytecode at startup to compile tenant field resolvers into efficient, directly-dispatchable method calls, avoiding the overhead of reflection-based invocation.

## Implementation Documentation

- [`impldocs/bytecodegen.md`](impldocs/bytecodegen.md) — Design of the bytecode generator and its testing strategy, including how generated classes are validated.
- [`impldocs/structure.md`](impldocs/structure.md) — Three-package architecture: `codegen/util` (shared utilities), `codegen/km` (Kotlin metadata handling), and `codegen/ct` (compile-time code generation).
- [`impldocs/learnings.md`](impldocs/learnings.md) — Intricacies of Kotlin-to-bytecode translation: lessons learned about how Kotlin constructs map to JVM bytecode.
