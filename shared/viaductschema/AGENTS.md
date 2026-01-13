# ViaductSchema Library

## Purpose

The ViaductSchema library provides a unified abstraction layer for working with GraphQL schemas within the Viaduct ecosystem. It defines a comprehensive type-safe interface for accessing and manipulating GraphQL schema elements (types, fields, directives, etc.) independent of the underlying schema representation.

**Key capabilities:**

* **Schema navigation**: Navigate and query GraphQL type systems programmatically
* **Schema projections**: Filter schemas based on custom rules
* **Cross-representation compatibility**: Abstract away differences between schema representations
* **Contract validation**: Ensure different representations conform to abstraction's contract

## Goals and Non-Goals

### Goals

1. **Unified Interface**: Provide a single, consistent API for working with GraphQL schemas regardless of their underlying representation (parsed SDL, validated schema, filtered view, etc.)

2. **Navigability**: References "up" (e.g., `containingDef`) and "across" (e.g., `baseTypeDef`) the schema AST as well as "down" (e.g., `fields`)

3. **Immutability**: All schema representations are immutable to enable safe sharing and caching

4. **Performance**: Support efficient schema operations through efficient construction, lazy evaluation and careful memory management

5. **Extensibility**: Allow new schema implementations to be added without changing consuming code

6. **Testability**: Provide contract-based tests that validate implementation correctness

### Non-Goals

1. **Schema Execution**: This library does NOT execute GraphQL queries. It provides an abstraction for schema representations only.

2. **GraphQL Parsing**: This library does NOT provide a parser for GraphQL SDL (it does provide a bridge to graphql-java's `TypeDefinitionRegistry`).

3. **Schema Validation Logic**: This library does NOT implement GraphQL schema validation (it does provide a bridge to graphql-java's validated `GraphQLSchema`).

## Architectural Overview

### Root Abstraction: `ViaductSchema`

The `ViaductSchema` interface is the entry point to the abstraction. It provides:

```kotlin
interface ViaductSchema {
    val types: Map<String, TypeDef>              // All type definitions
    val directives: Map<String, Directive>       // All directive definitions
    val queryTypeDef: Object?                     // Root query type
    val mutationTypeDef: Object?                  // Root mutation type
    val subscriptionTypeDef: Object?              // Root subscription type
}
```

### Type Hierarchy

The library models GraphQL's type system through a comprehensive hierarchy of nested interfaces:

```
Def (base for all definitions)
├── TypeDef (all type definitions)
│   ├── Scalar
│   ├── Enum
│   ├── Union
│   └── Record (types with fields)
│       ├── Input
│       ├── Interface
│       └── Object
├── Field (field definitions)
├── EnumValue (enum value definitions)
├── Directive (directive definitions)
└── Arg (argument definitions)
    ├── FieldArg
    └── DirectiveArg
```

TypeDef also has predicates `isSimple`, `isComposite`, `isInput`, and `isOutput` for categorizing types.

### Type Expressions

The `ViaductSchema.TypeExpr` type represents GraphQL type expressions (e.g., `String!`, `[Int]`, `[User!]!`):

* **baseTypeDef**: The underlying type definition (e.g., `String`, `Int`, `User`)
* **baseTypeNullable**: Whether the base type is nullable
* **listNullable**: Bit vector representing nullability at each list nesting level

Unlike most types nested in `ViaductSchema`:

* `TypeExpr` is an abstract class, providing the implementation for list- and non-null "wrapping" and delegating just `baseTypeDef` to implementations of `ViaductSchema`
* `TypeExpr` has value-equality semantics, versus reference-equality for most of the other nested types.  Value equality is implemented in the `TypeExpr` abstract class.

### Extensions

GraphQL supports type extensions, and this library models them explicitly through `Extension` interfaces:

* Each extensible type (Object, Interface, Enum, Input, Union) has an `extensions` property
* Extensions track which is the "base" definition vs. extensions
* Extensions carry their own applied directives
* Source locations are preserved per extension

### Applied Directives

The `AppliedDirective` interface represents directive applications (e.g., `@deprecated(reason: "Use newField")`):

* **name**: Directive name
* **arguments**: Map of argument names to resolved values
* Implements value-type semantics (equality based on content, not identity)

**Important:** the `arguments` of an `AppliedDirective` is expected to contain the values of _all_ arguments, not just arguments that are explicitly provided in the schema.  It's impossible to tell in `ViaductSchema` when the input schema text explicitly provided an argument value versus dependended on a default value (including a default value of `null` for nullable arguments).

## Implementation Summary

### Primary Implementations

The library provides three main implementations of `ViaductSchema`, plus a binary serialization format:

#### 1. GJSchemaRaw

**Purpose**: Wraps graphql-java's `TypeDefinitionRegistry` - a parsed but not semantically validated schema.

**Key characteristics:**

* Wraps the `graphql.language.*` classes
* Fast to construct (no validation overhead)
* Supports partial schemas (no required query root)
* Used primarily during build-time code generation

**When to use**: Typically, when the input schema has already been validated (eg, by a previous step in the build process) and you are looking for higher performance

#### 2. GJSchema

**Purpose**: Wraps graphql-java's `GraphQLSchema` - a parsed, validated, and semantically analyzed schema.

**Key characteristics:**

* Wraps the `graphql.schema.*` classes
* Slower to construct because `GraphQLSchema` is slower to construct
* Requires a query root type

**When to use**: When you need guaranteed schema validity and can afford the validation cost.

#### 3. Binary Schema Format

**Purpose**: A compact binary serialization format for `ViaductSchema` that enables fast loading and reduced memory footprint.

**Key characteristics:**

* Serializes any `ViaductSchema` to a binary format optimized for fast deserialization
* String deduplication via an interned string table
* Significantly faster to load than parsing SDL text (10-20x speedup on large schemas)
* Lower memory overhead than graphql-java's object model
* Round-trip tested: binary → ViaductSchema → binary produces identical output

**When to use**: For production schema loading where startup time and memory usage matter. Schemas can be pre-compiled to binary format during build time, then loaded quickly at runtime.

**API**:
```kotlin
// Write a schema to binary format
writeBSchema(outputStream, viaductSchema)

// Read a schema from binary format
val schema: ViaductSchema = readBSchema(inputStream)
```

#### 4. FilteredSchema

**Purpose**: Projects an existing `ViaductSchema` through a filter to create a restricted view.

**Key characteristics:**

* Wraps another `ViaductSchema` implementation
* Applies filtering rules via `SchemaFilter` interface
* Maintains linkage to underlying filtered schema
* Generic over the underlying schema implementation
* **Important:** filtered schemas do _not_ need to be valid GraphQL schemas

**Filtering capabilities:**

* Remove types, fields, enum values
* Remove interface implementations
* Filter union members
* Custom filtering logic via `SchemaFilter` interface

So far the primary use-case is "compilation schemas," i.e., a projection of an application's full schema to just the parts needed by a particular tenant module.

**Example**:
```kotlin
val filteredSchema = baseSchema.filter(
    filter = object : SchemaFilter {
        override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) =
            !typeDef.hasAppliedDirective("internal")
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("admin")
        // ... other filter methods
    }
)
```

## Testing Expectations

The library includes comprehensive contract-based testing infrastructure. All `ViaductSchema` implementations should:

1. **Use contract testing**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Validate type signatures**: Ensure proper Kotlin type relationships
3. **Test edge cases**: Handle empty schemas, cyclic types, complex extensions
4. **Verify invariants**: Schemas must satisfy structural invariants

See [AGENTS_TESTING.md](AGENTS_TESTING.md) for detailed testing guidelines.

## Common Patterns

### Finding a Field

```kotlin
val schema: ViaductSchema = ...
val userType = schema.types["User"] as ViaductSchema.Object
val nameField = userType.field("name")
```

### Navigating to Nested Fields

```kotlin
val addressStreet = userType.field(listOf("address", "street"))
```

### Checking for Directives

```kotlin
if (field.hasAppliedDirective("deprecated")) {
    // Handle deprecated field
}
```

### Filtering by Directive

```kotlin
val publicSchema = schema.filter(
    filter = object : SchemaFilter {
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("internal")
        // inhereit default "always include" logic for other methods
    }
)
```

### Walking the Schema

```kotlin
fun collectAppliedDirectives(schema: ViaductSchema): Set<ViaductSchema.AppliedDirective> {
    val result = mutableSetOf<ViaductSchema.AppliedDirective>()
    fun visit(def: ViaductSchema.Def) {
        result.addAll(def.appliedDirectives) // all def's have applied directives
        when (def) {
            is ViaductSchema.HasArgs -> // Handles `Directive` and `Field`
                def.args.forEach(::visit)
            is ViaductSchema.Record -> // Handles `Input`, `Interface` and `Object`
                def.fields.forEach(::visit)
            is ViaductSchema.Enum -> def.values.forEach(::visit)
            else -> { } // do nothing more for Scalar and Union
        }
    }
    schema.directives.values.forEach(::visit)
    schema.types.values.forEach(::visit)
    return result
}
```

## Contribution Guidelines

When modifying this library:

1. **Maintain contracts**: Don't break `ViaductSchemaContract` tests
2. **Preserve immutability**: Never expose mutable state
4. **Test with real schemas**: Use actual Viaduct tenant schemas for integration testing
5. **Consider performance**: This code runs during every build
6. **Update this doc**: Keep AGENTS.md current with architectural changes
