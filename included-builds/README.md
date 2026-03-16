# Included Builds Structure

This directory contains the layered build structure for Viaduct to eliminate circular dependencies.

## Layer Architecture

1. **foundation** - Base shared libraries with no dependencies
   - All `shared/*` modules (utils, logging, graphql, arbitrary, codegen, dataloader, deferred, invariants, viaductschema)
   - Uses only basic `build-logic` plugins

2. **core** - Core domain modules
   - `engine/*`, `service/*`, `tenant/api`, `tenant/runtime`, `snipped/errors`
   - Depends on foundation layer

3. **codegen** - Code generation module
   - Only `tenant/codegen` (without test-classdiff plugin)
   - Depends on foundation and core layers

4. **main build** (root) - Applications and testing
   - Test apps, tools, docs, runtime-publisher

## Benefits

- **No circular dependencies**: Clean layered architecture
- **Minimal disruption**: All source code stays in place
- **Clear separation**: Each layer has defined responsibilities
- **Future-ready**: Easy to restructure further if needed
