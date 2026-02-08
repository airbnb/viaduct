# For Library Maintainers

This file is for developers who maintain or extend the ViaductSchema library itself.

## Unified Implementation: SchemaWithData

All schema flavors share a single `internal` implementation class, `SchemaWithData`, which directly implements `ViaductSchema`. The key insight is that each node has an optional `data: Any?` property that stores flavor-specific auxiliary data:

```kotlin
internal class SchemaWithData(...) : ViaductSchema {
    sealed class Def {
        abstract val data: Any?  // Flavor-specific data
        // ...
    }
    // Nested classes: TypeDef, Object, Interface, Field, etc.
}
```

The different "flavors" are distinguished by what they store in `data`:

| Flavor | What's in `data` | Use Case |
|--------|------------------|----------|
| Binary | `null` | Fast loading, compact storage |
| GJSchema | `graphql.schema.*` types | Validated schema access |
| GJSchemaRaw | `TypeDefData` with `graphql.language.*` | Fast parsing, build-time codegen |
| Filtered | Unfiltered `ViaductSchema.Def` | Schema projections |

## Internal Factory Functions

These are top-level `internal` functions that return `SchemaWithData` (not `ViaductSchema`), providing access to the `data` property for flavor-specific operations.

**GJSchema** (`viaduct.graphql.schema.graphqljava`):
```kotlin
internal fun gjSchemaFromSchema(gjSchema: GraphQLSchema): SchemaWithData
internal fun gjSchemaFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
internal fun gjSchemaFromFiles(inputFiles: List<File>): SchemaWithData
internal fun gjSchemaFromURLs(inputFiles: List<URL>): SchemaWithData
```

**GJSchemaRaw** (`viaduct.graphql.schema.graphqljava`):
```kotlin
internal fun gjSchemaRawFromSDL(sdl: String): SchemaWithData
internal fun gjSchemaRawFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
internal fun gjSchemaRawFromFiles(inputFiles: List<File>): SchemaWithData
internal fun gjSchemaRawFromURLs(inputFiles: List<URL>): SchemaWithData
```

**Binary** (`viaduct.graphql.schema.binary`):
```kotlin
internal fun readBSchema(input: InputStream): SchemaWithData
internal fun writeBSchema(schema: ViaductSchema, output: OutputStream)
```

**Filtered** (`viaduct.graphql.schema`):
```kotlin
internal fun filteredSchema(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    ...
): SchemaWithData
```

## Two-Phase Construction Pattern

ViaductSchema implementations must handle circular references between types—for example, an `Object` type needs references to its `Interface` supers, while an `Interface` needs references to its implementing `Object` types. All flavors address this using a two-phase construction pattern:

**Phase 1 (Shell Creation)**: All type definition and directive "shells" are created with just their names (and any raw source data in `data`). These shells are added to type/directive maps.

**Phase 2 (Population)**: Each shell is populated with its full data—including cross-references to other types—via a `populate()` method. At this point the type map is fully populated, so cross-references can be resolved directly.

Each `populate()` method includes an idempotency guard (`check(mFoo == null)`) to ensure it's called exactly once, and properties use `guardedGet()` accessors to verify population before access.

## Decoders

Each flavor uses a **decoder** class that transforms source data into `SchemaWithData` elements:

| Flavor | Decoder Class | Source Data |
|--------|---------------|-------------|
| GJSchema | GraphQLSchemaDecoder | `graphql.schema.GraphQLSchema` |
| GJSchemaRaw | TypeDefinitionRegistryDecoder | `graphql.language.TypeDefinitionRegistry` |
| Binary | DefinitionsDecoder | Binary input stream |
| Filtered | FilteredSchemaDecoder | Another `ViaductSchema` |

The decoder has access to the fully-populated type map, so it can resolve type references and build `Extension`, `Field`, and other nested objects with direct references rather than deferred lookups.

## Factory Callbacks for Bidirectional Containment

Nested objects like `Extension`, `Field`, `EnumValue`, and `Arg` are immutable from creation—they receive all their data in their constructors. For bidirectional containment relationships (e.g., a `Field` contains `args`, but each `FieldArg` references back to the `Field`), the pattern uses a **factory callback**: the container's constructor takes a `memberFactory: (Container) -> List<Member>` parameter, invokes it with `this`, and the factory creates members with the back-reference already set.

## Extension Properties for Type-Safe Data Access

Each flavor provides `internal` extension properties that cast `data` to the appropriate type:

**GJSchema** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
internal val SchemaWithData.Object.gjDef: GraphQLObjectType
internal val SchemaWithData.Interface.gjDef: GraphQLInterfaceType
internal val SchemaWithData.Field.gjOutputDef: GraphQLFieldDefinition
internal val SchemaWithData.Field.gjInputDef: GraphQLInputObjectField
// etc.
```

**GJSchemaRaw** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
internal val SchemaWithData.TypeDef.gjrDef: TypeDefinition<*>
internal val SchemaWithData.TypeDef.gjrExtensionDefs: List<TypeDefinition<*>>
internal val SchemaWithData.Object.gjrDef: ObjectTypeDefinition
// etc.
```

**Filtered** (in `viaduct.graphql.schema`):
```kotlin
internal val SchemaWithData.Def.unfilteredDef: ViaductSchema.Def
internal val SchemaWithData.Object.unfilteredDef: ViaductSchema.Object
internal val SchemaWithData.Field.unfilteredDef: ViaductSchema.Field
// etc.
```

## Testing Expectations

The library includes comprehensive testing infrastructure organized into black-box tests (verifying GraphQL semantics across implementations) and glass-box tests (verifying implementation-specific behavior). All `ViaductSchema` flavors should:

1. **Use contract testing**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Use black-box testing**: Run the shared `TestSchemas` cases through your implementation
3. **Add glass-box tests**: Test implementation-specific behavior (encoding limits, caching, error handling)
4. **Verify invariants**: Schemas must satisfy structural invariants

See [TESTING.md](TESTING.md) for detailed testing guidelines for contributors.

See [TEST_FIXTURES.md](TEST_FIXTURES.md) for documentation on test utilities and sample schemas.

## Contribution Guidelines

When modifying this library:

1. **Maintain contracts**: Don't break `ViaductSchemaContract` tests
2. **Preserve immutability**: Never expose mutable state
3. **Test with real schemas**: Use actual Viaduct tenant schemas for integration testing
4. **Consider performance**: This code runs during every build
5. **Update this doc**: Keep AGENTS.md current with architectural changes
