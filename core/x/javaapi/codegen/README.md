# Java GRTs Code Generation CLI

This package contains CLI commands for generating Java GraphQL Representational Types (GRTs) from schema files.

## Commands Overview

### JavaGRTsGenerator

Generates **Java source code** for GraphQL types from schema files.

**Key Benefits:**

- Pure Java output for Java-first projects
- Full IDE support (autocomplete, navigation, refactoring)
- Source-level debugging capabilities
- Supports type extensions (`extend enum`, `extend type`, `extend input`, `extend interface`, `extend union`)

## Building

Build the fat JAR with all dependencies:

```bash
./gradlew :core:x:javaapi:x-javaapi-codegen:shadowJar
```

The JAR will be created at `x/javaapi/codegen/build/libs/java-grts-codegen-<version>.jar`.

## Usage

```bash
java -jar x/javaapi/codegen/build/libs/java-grts-codegen-<version>.jar \
  --schema_files /path/to/schema.graphqls \
  --grt_output_dir /path/to/generated/grts \
  --grt_package com.mycompany.graphql.types \
  --resolver_generated_dir /path/to/generated/resolvers \
  --tenant_package com.mycompany.tenant \
  --verbose
```

**Parameters:**

| Parameter                   | Required | Description                                                                                                                          |
|-----------------------------|----------|--------------------------------------------------------------------------------------------------------------------------------------|
| `--schema_files`            | Yes      | Comma-separated list of GraphQL schema files (absolute paths)                                                                        |
| `--grt_output_dir`          | Yes      | Output directory for GRT files (written to package subdirectories)                                                                   |
| `--grt_package`             | No*      | Java package name for generated GRT types                                                                                            |
| `--grt_package_file`        | No*      | File containing the Java package name for generated GRT types                                                                        |
| `--resolver_generated_dir`  | Yes      | Output directory for resolver files (written to package subdirectories)                                                              |
| `--tenant_package`          | No       | Java package name for resolver bases (uses `{tenantPackage}.resolverbases`); defaults to grt_package                                 |
| `--tenant_package_file`     | No       | File containing the Java package name for resolver bases                                                                             |
| `--grt_output_archive`      | No       | Output srcjar path; if set, GRT output directory is zipped into this archive and then deleted                                        |
| `--resolver_output_archive` | No       | Output srcjar path; if set, resolver output directory is zipped into this archive and then deleted                                   |
| `--include_root_types`      | No       | If set, include Query/Mutation/Subscription GRTs (Mutation and Subscription are deleted post-gen due to invalid Java 9+ field names) |
| `--verbose`                 | No       | Print generation results (file list and type counts)                                                                                 |

\* Exactly one of `--grt_package` or `--grt_package_file` must be provided.

## Generated Output

### GRT Types

The GRT output directory will contain Java source files organized by package:

```
grt_output_dir/
└── com/
    └── mycompany/
        └── graphql/
            └── types/
                ├── MyEnum.java
                ├── MyObject.java
                ├── MyInput.java
                ├── MyInterface.java
                └── MyUnion.java
```

### Resolver Base Classes

Resolver files are written to package subdirectories under the resolver output directory.
The package is `{tenant_package}.resolverbases`:

```
resolver_generated_dir/
└── com/
    └── mycompany/
        └── tenant/
            └── resolverbases/
                ├── UserResolvers.java
                ├── ListingResolvers.java
                └── QueryResolvers.java
```

Each resolver file contains abstract base classes for fields with the `@resolver` directive that tenant developers extend to implement resolvers.

## Supported Types

- **Enums** - Including extended enums (`extend enum`)
- **Objects** - GraphQL object types with fields, getters/setters, and builder pattern
- **Inputs** - GraphQL input types with fields, getters/setters, and builder pattern
- **Interfaces** - GraphQL interface types
- **Unions** - GraphQL union types
- **Argument Types** - Java types for field arguments
- **Resolvers** - Abstract base classes for fields with `@resolver` directive

## Testing

```bash
./gradlew :core:x:javaapi:x-javaapi-codegen:test
```
