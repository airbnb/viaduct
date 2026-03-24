# For External ViaductSchema Implementers

This file is for developers who want to create their own `ViaductSchema` implementation backed by a different underlying representation.

## Contract Test Suites

The library provides two contract test suites in `testFixtures` to verify your implementation's correctness:

### ViaductSchemaContract

Tests **behavioral correctness**—that your implementation behaves correctly according to GraphQL semantics.

```kotlin
class MySchemaContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema {
        return MySchema.fromSDL(schema)
    }
}
```

This interface provides comprehensive tests for:

- Default value handling for fields and arguments
- Field path navigation
- Override detection (`isOverride`)
- Extension lists and applied directives
- Root type referential integrity
- Type expression properties

### ViaductSchemaSubtypeContract

Tests **type structure**—that your implementation's nested types properly subtype `ViaductSchema`'s nested interfaces.

```kotlin
class MySchemaSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass(): KClass<*> = MySchema::class
}
```

This class uses Kotlin reflection to verify:

- All required nested classes exist (`Def`, `TypeDef`, `Field`, `Arg`, etc.)
- The class hierarchy is correct (e.g., `TypeDef` extends `TopLevelDef`)
- Return types are proper subtypes (e.g., `Field.containingDef` returns your implementation's `Record` type)

**Optional customization:**

- Override `skipExtensionTests = true` if your implementation delegates extension fields without wrapping them
- Override `classes` if your implementation uses non-standard nested class names

## Black-Box Testing with TestSchemas

In addition to the contracts, use the shared `TestSchemas` cases through your implementation for comprehensive coverage.

See [TEST_FIXTURES.md](TEST_FIXTURES.md) for documentation on test utilities and sample schemas available.

