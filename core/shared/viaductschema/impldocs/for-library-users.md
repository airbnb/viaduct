# For Library Consumers

This file is for developers who use ViaductSchema to work with GraphQL schemas in their applications.

## The ViaductSchema Interface

The `ViaductSchema` interface is the entry point to the abstraction:

```kotlin
interface ViaductSchema {
    val types: Map<String, TypeDef>
    val directives: Map<String, Directive>
    val queryTypeDef: Object?
    val mutationTypeDef: Object?
    val subscriptionTypeDef: Object?
}
```

All operations go through this interface. You never need to work with implementation classes directly.



## Creating Schemas

ViaductSchema provides several factory methods as extension functions on `ViaductSchema.Companion`. Choose based on your source data and requirements.

### From GraphQL-Java Validated Schema

**Import:** `viaduct.graphql.schema.graphqljava.extensions`

```kotlin
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(registry: TypeDefinitionRegistry): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(schema: GraphQLSchema): ViaductSchema
```

**Characteristics:**

- Performs full GraphQL semantic validation
- Requires a query root type
- Slower to construct due to validation overhead
- Use when you need guaranteed schema validity

### From GraphQL-Java Raw Registry

**Import:** `viaduct.graphql.schema.graphqljava.extensions`

```kotlin
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(registry: TypeDefinitionRegistry): ViaductSchema
```

**Characteristics:**

- Fast to construct (no validation overhead)
- Supports partial schemas (no required query root)
- Use when your schema is already validated by other means (e.g., a previous build step)

### From Binary Format

**Import:** `viaduct.graphql.schema.binary.extensions`

```kotlin
// Reading
ViaductSchema.Companion.fromBinaryFile(file: File): ViaductSchema
ViaductSchema.Companion.fromBinaryFile(input: InputStream): ViaductSchema

// Writing
fun ViaductSchema.toBinaryFile(file: File)
fun ViaductSchema.toBinaryFile(output: OutputStream)
```

**Characteristics:**

- 10-20x faster to load than parsing SDL text
- Lower memory overhead than graphql-java's object model
- Use for production schema loading where startup time matters
- Pre-compile schemas to binary during build, load quickly at runtime

### Creating Filtered Schemas

```kotlin
fun ViaductSchema.filter(filter: SchemaFilter, options: SchemaInvariantOptions): ViaductSchema
```

**Example:**
```kotlin
val publicSchema = schema.filter(
    filter = object : SchemaFilter {
        override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) =
            !typeDef.hasAppliedDirective("internal")
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("admin")
    }
)
```

**Characteristics:**

- Creates a restricted view of another schema
- Filtered schemas do _not_ need to be valid GraphQL schemas
- Primary use-case: "compilation schemas" (projections for tenant modules)

## Choosing a Schema Flavor

| Need | Recommended Approach |
|------|---------------------|
| Validated schema, can afford validation cost | `fromGraphQLSchema()` |
| Fast construction, schema already validated | `fromTypeDefinitionRegistry()` |
| Production loading, startup time matters | `fromBinaryFile()` |
| Restricted view of existing schema | `filter()` |

## Common Usage Patterns

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
        // inherit default "always include" logic for other methods
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

## Type Hierarchy

The library models GraphQL's type system through a comprehensive hierarchy of nested interfaces:

```
Def
├── TypeDef
│   │
│   ├── HasExtensions
│   │   └── HasExtenionsWithSupers
│   │
│   ├── Enum - extends HasExtensions
│   ├── Record - extends HasExtensions
│   │   ├── Input
│   │   ├── Interface
│   │   └── Object
│   ├── Scalar
│   └── Union - extends HasExtensions
│
├── HasArgs
│
├── Directive - extends HasArgs
├── EnumValue
└── HasDefaultValue
    ├── Field ─ extends HasArgs
    └── Arg
        ├── FieldArg
        └── DirectiveArg
AppliedDirective
Extension
└── ExtensionWithSupers
SourceLocation
TypeExpr
```

TypeDef also has predicates `isSimple`, `isComposite`, `isInput`, and `isOutput` for categorizing types.

## Type Expressions

The `ViaductSchema.TypeExpr` type represents GraphQL type expressions (e.g., `String!`, `[Int]`, `[User!]!`):

- **baseTypeDef**: The underlying type definition (e.g., `String`, `Int`, `User`)
- **baseTypeNullable**: Whether the base type is nullable
- **listNullable**: Bit vector representing nullability at each list nesting level

TypeExpr has value-equality semantics (versus reference-equality for most other nested types).

## Extensions

GraphQL supports type extensions, and this library models them explicitly through `Extension` interfaces:

- Each extensible type (Object, Interface, Enum, Input, Union) has an `extensions` property
- Extensions track which is the "base" definition vs. extensions
- Extensions carry their own applied directives
- Source locations are preserved per extension

## Applied Directives

The `AppliedDirective` interface represents directive applications (e.g., `@deprecated(reason: "Use newField")`):

- **name**: Directive name
- **arguments**: Map of argument names to resolved values
- Implements value-type semantics (equality based on content, not identity)

**Important:** the `arguments` of an `AppliedDirective` contains the values of _all_ arguments, not just arguments explicitly provided in the schema. It's impossible to tell whether the input schema explicitly provided an argument value versus depended on a default value.
