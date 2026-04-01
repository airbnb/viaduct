# Version HEAD

## Features

- make UnsetFieldException a subclass of TenantUsageException (574f78a7) by @fireboy1919
- wrap ExecutionContext boundary methods with handleTenantAPIErrors (9f22737f) by @fireboy1919
- add first-class diagnostics to Viaduct Gradle plugins (8b595821) by @rstata
- Adds a DSL-style `of` companion object to GRTS-generated classes, enabling cleaner trailing-lambda syntax for constructing Viaduct objects and connection edges. (dd899d07) by @geovannefduarte
- KSP login introduction to tenant/ksp package. (c2267721) by @gokhan-ozgozen

## Bug Fixes

- wrap nested EngineObjectData in InstrumentedEngineObjectData so instrumentation propagates through the full selection-set hierarchy (74222283) by @vickeyyeh
- fix missing resolver attribution on subquery execution (25082be6) by @vickeyyeh
- Fix build-and-test.yml script java-api library in dokka generation. (f7a8a86a) by @nmarsollier

## Performance Improvements

- reduce withContext overhead in sync value computation path (b8c62152) by @vickeyyeh

## Documentation

- Adds and refacotrs kdocs and documentation for Connections. (5109ab19) by @geovannefduarte

## Refactoring

- remove redundant globalIDCodec from bootstrapper chain (0240d8c8) by @geovannefduarte
- Explicit tooling classpaths for codegen and serve (d16de278) by @rstata
- clean up ErrorReporter.Metadata fields (42f52807) by @fireboy1919
- remove afterEvaluate from ViaductClassDiffPlugin (56b6fd37) by @rstata

## Chores

- version bump by @gokhan.ozgozen
- bug(engine): Restore DataFetchingEnvironment to error metadata (8b8a0fb2) by @rstata
- bump version to 0.27.0-SNAPSHOT (#307) by @nmarsollier

## Continuous Integration

- extend starter demoapp matrix to Java 21 and drop redundant clean (80f25260) by @geovannefduarte

## Build System

- Remove release preparation workflows (#309) by @rstata
- Restore detekt/ktlint CI and fix build-scan upload steps (#308) by @rstata

