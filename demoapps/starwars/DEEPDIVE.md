# Star Wars GraphQL Demo - Deep Dive

> For quick start instructions, see [README.md](README.md). This document contains detailed technical information.

This demo application showcases a comprehensive GraphQL API implementation using Viaduct's custom directives and batch resolvers. The demo features Star Wars characters, films, planets, and other entities with full relationship mapping and optimized N+1 query prevention.

## Viaduct Core Functionality

### Nodes: The Foundation of Entity Resolution

Node resolvers are the backbone of GraphQL entity resolution in Viaduct. Every entity that you wish to fetch individually should be extend the global `node` interface using a GlobalID. This design pattern enables powerful batch resolution capabilities and provides a consistent interface for retrieving any entity by its identifier.

#### Global IDs: The Universal Key System

GlobalIDs in Viaduct are base64-encoded strings that combine two pieces of information:
- **Type**: The GraphQL type name (e.g., "Character", "Film", "Planet")
- **Internal ID**: Your application's internal identifier for that entity

```kotlin
// Example: Character with internal ID "1" becomes "Q2hhcmFjdGVyOjE="
// Decoded: "Character:1"
val globalId = GlobalID.fromTypeAndId("Character", "1")
val encoded = globalId.toString() // "Q2hhcmFjdGVyOjE="
```

**Key insight**: GlobalIDs are purely for retrieval via `node` queries - they should not exposed to users as readable identifiers. Unlike traditional database applications where you might expose primary keys directly in your UI, GlobalIDs are opaque identifiers used internally by GraphQL for efficient entity resolution.

#### Node Resolver Implementation

Every entity type requires a node resolver that can retrieve the entity by its GlobalID:

```kotlin
@Component
class CharacterNodeResolver : NodeResolvers.Character() {
    override suspend fun resolve(globalId: GlobalID): Character? {
        val internalId = globalId.internalID
        return StarWarsData.characters[internalId]
    }
}
```

**Best practices for node resolvers**:
1. **Always implement for entities**: Every type that represents a business entity should have a node resolver
2. **Keep logic simple**: Node resolvers should focus solely on entity retrieval by ID
3. **Handle missing entities gracefully**: Return null for non-existent IDs rather than throwing exceptions
4. **Batch when possible**: If your data layer supports it, implement batch node resolution

#### The Batch Resolution Advantage

The real power of node resolvers emerges when combined with batch field resolvers. Consider this query:

```graphql
query {
  allCharacters(limit: 100) {
    homeworld {
      name
    }
  }
}
```

Without batch resolution, this creates the classic N+1 problem:
- 1 query to get all characters
- 100 individual queries to get each character's homeworld

With proper node resolvers and batch field resolvers, this becomes:
- 1 query to get all characters
- 1 batch query to resolve all homeworlds

The node resolver pattern makes this optimization possible because entities are consistently retrievable by GlobalID, allowing the batch resolver to efficiently group and resolve multiple requests.

### Field Resolvers: Custom Logic for Complex Fields

Field resolvers handle the computation of individual fields that require more than simple property access. They complement node resolvers by providing the business logic layer above basic entity retrieval.

#### When to Use Field Resolvers

Field resolvers are appropriate when:
- **Computed fields**: Values that don't exist in your data store but are calculated from other fields
- **Cross-entity relationships**: Fields that require loading related entities
- **Performance optimization**: Fields that benefit from batching multiple requests
- **Business logic**: Fields that require complex rules or transformations

#### Single Field Resolvers

For simple computed fields, use standard field resolvers:

```kotlin
@Resolver("name homeworld { name }")
class CharacterDisplayNameResolver : CharacterResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String {
        val character = ctx.objectValue
        val homeworld = character.getHomeworld()
        return "${character.getName()} of ${homeworld?.getName() ?: "Unknown"}"
    }
}
```


#### Batch Field Resolvers: The Performance Game-Changer

Batch field resolvers process multiple field requests simultaneously, dramatically improving performance when the same field is requested across multiple entities:

```kotlin
@Resolver(objectValueFragment = "fragment _ on Character { id }")
class CharacterFilmCountBatchResolver : CharacterResolvers.FilmCount() {
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Int>> {
        // Extract all character IDs from the batch
        val characterIds = contexts.map { it.objectValue.getId().internalID }

        // Single database query to get film counts for all characters
        val filmCounts = StarWarsData.getFilmCountsForCharacters(characterIds)

        // Map results back to contexts in the same order
        return contexts.map { ctx ->
            val characterId = ctx.objectValue.getId().internalID
            FieldValue.ofValue(filmCounts[characterId] ?: 0)
        }
    }
}
```

**Critical batch resolver principles**:
1. **One-to-one result mapping**: Always return results in the same order as input contexts
2. **Minimize data layer calls**: The entire point is to replace N calls with 1 call
3. **Handle missing data**: Gracefully handle cases where batch data is incomplete

### Resolver Integration Patterns

#### Standard Entity Pattern
Every entity follows this pattern:
1. **Node resolver** for GlobalID-based retrieval via `node` queries
2. **Batch field resolvers** for expensive computed fields that benefit from batching
3. **Single field resolvers** for simple computed fields

```kotlin
// Node resolution for `node(id: "Q2hhcmFjdGVyOjE=")` queries
@Component
class PlanetNodeResolver : NodeResolvers.Planet() { /* ... */ }

// Batch field resolution for performance
@Resolver(objectValueFragment = "fragment _ on Planet { id }")
class PlanetResidentsBatchResolver : PlanetResolvers.Residents() { /* ... */ }

// Simple field resolution
@Resolver("name climate")
class PlanetDescriptionResolver : PlanetResolvers.Description() { /* ... */ }
```

#### The Entity Resolution Flow

1. **Query parsing**: GraphQL query identifies entities needed via `node(id:)` or other entry points
2. **Node resolution**: Node resolvers retrieve entities by GlobalID when accessed via `node` field
3. **Field resolution**: Field resolvers compute additional fields, using batch resolution where possible
4. **Result assembly**: Viaduct combines node and field data into the final response

This separation allows for clean code organization and optimal performance through batching at the field level.

## Custom Directives Demonstrated

### @backingData
**Purpose**: Specifies a backing data class for complex field resolution.

**Usage**: Applied to fields to specify the implementation class for data transformation.

**Example**:
```graphql
type Character {
  films(limit: Int): [Film]
  @resolver
  @backingData(class: "starwars.character.FilmConnection")
}
```

**Implementation**: The specified class provides data transformation logic for GraphQL fields.

### @scope
**Purpose**: Restricts schema availability to specific tenants or contexts, enabling multi-tenant GraphQL schemas.

**Usage**: Applied to types, interfaces, and query fields to limit access to specific scopes.

**Example**:
```graphql
type Query @scope(to: ["default"]) {
  allCharacters: [Character]
}

type Species @scope(to: ["default", "extras"]) {
  name: String
  culturalNotes: String @scope(to: ["extras"])  # Only available with "extras" scope
}
```

> To query scoped fields, include the `X-Viaduct-Scope` header in your request:
> ```
> {
>     "X-Viaduct-Scope": "extras"
> }
> ```

**Implementation**: The demo demonstrates both default scope access and restricted "extras" scope fields.

### @idOf
**Purpose**: Specifies global ID type association and validation for proper type checking.

**Usage**: Applied to ID fields and arguments to associate them with specific GraphQL types.

**Example**:
```graphql
type Query {
  character(id: ID! @idOf(type: "Character")): Character
}

type Character {
  id: ID! @idOf(type: "Character")
}
```

**Implementation**: Enables type-safe GlobalID handling and validation across the GraphQL schema.

### @oneOf
**Purpose**: Ensures input objects have exactly one non-null field, useful for union-like input types.

**Usage**: Applied to input types where only one option should be specified.

**Example**:
```graphql
input CharacterSearchInput @oneOf {
  byName: String
  byId: ID
  byBirthYear: String
}

type Query {
  searchCharacter(search: CharacterSearchInput!): Character
}
```

**Implementation**: Validates that exactly one search criterion is provided, preventing ambiguous queries.

### Variables and Variable Providers
**Purpose**: Enable dynamic field selection and conditional GraphQL queries through runtime variable computation.

**Usage**: Variables can be bound to resolver arguments or computed dynamically using VariableProvider classes to control which fields are selected at the GraphQL execution level.

Viaduct supports three approaches for dynamic field resolution:

#### 1. Variables with @Variable and fromArgument
Variables can be bound directly to resolver arguments to control GraphQL directive evaluation:

```kotlin
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear @include(if: $includeDetails)
        height @include(if: $includeDetails)
        mass @include(if: $includeDetails)
    }
    """,
    variables = [Variable("includeDetails", fromArgument = "includeDetails")]
)
class CharacterProfileResolver {
    // When includeDetails=true, all fields are available
    // When includeDetails=false, only name is selected
}
```

**Benefits**: GraphQL-level optimization, declarative field selection, efficient data fetching.

#### 2. Argument-Based Statistics Logic
For practical demo purposes, the character stats use argument-based conditional logic:

```kotlin
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear
        height
        species {
            name
        }
    }
    """
)
class CharacterStatsResolver {
    override suspend fun resolve(ctx: Context): String? {
        val args = ctx.arguments
        return when {
            isValidAgeRange(args.minAge, args.maxAge) -> buildDetailedStats(ctx.objectValue)
            else -> buildBasicStats(ctx.objectValue)
        }
    }
}
```

**Benefits**: Simple implementation, full access to all fields, easy to debug and maintain.

*Note: The full VariableProvider API with dynamic computation is available in the complete Viaduct runtime but simplified here for demo clarity.*

#### 3. Argument-Based Conditional Logic
For simpler cases, traditional argument processing within resolvers:

```kotlin
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear
        eyeColor
        hairColor
    }
    """
)
class CharacterFormattedDescriptionResolver {
    override suspend fun resolve(ctx: Context): String? {
        return when (ctx.arguments.format) {
            "detailed" -> buildDetailedDescription(ctx.objectValue)
            "year-only" -> buildYearOnlyDescription(ctx.objectValue)
            else -> ctx.objectValue.getName()
        }
    }
}
```

**Benefits**: Simplicity, full Kotlin language features, easy debugging.

**Example Schema**:
```graphql
type Character {
  # Variables with fromArgument - demonstrates GraphQL-level field selection
  characterProfile(includeDetails: Boolean = false): String @resolver

  # Argument-based statistics - practical implementation for demos
  characterStats(minAge: Int, maxAge: Int): String @resolver

  # Argument-based conditional logic - flexible formatting
  formattedDescription(format: String = "default"): String @resolver
}
```

## Data Model

The demo includes comprehensive Star Wars data:

### Characters
- Luke Skywalker (Tatooine, Human, pilots X-wing)
- Princess Leia (Alderaan, Human)
- Han Solo (Corellia, Human, pilots Millennium Falcon)
- Darth Vader (Tatooine, Human)
- Obi-Wan Kenobi (Stewjon, Human)

### Films
- A New Hope (Episode IV)
- The Empire Strikes Back (Episode V)
- Return of the Jedi (Episode VI)

### Planets
- Tatooine (desert world)
- Alderaan (destroyed planet)
- Corellia (industrial world)
- Stewjon (Obi-Wan's homeworld)

### Species
- Human (with extras scope: cultural notes, rarity level, special abilities)

### Starships & Vehicles
- Millennium Falcon (Han's ship)
- X-wing (Luke's fighter)
- Speeder bikes

## Architecture

### In-Memory Storage
The demo uses `StarWarsData` object for in-memory data storage with relationship mappings:
```kotlin
val characterFilmRelations = mapOf(
  "1" to listOf("1", "2", "3"), // Luke in all three films
  // ...
)
```

### List Implementation
GraphQL lists are implemented using backing data classes:
- Simple list queries for characters, films, planets, species, vehicles
- Relationship lists for character-film, character-starship mappings
- Optional limit parameter to control the number of returned items

## Key Features

1. **Batch Resolver Optimization**: Prevents N+1 queries with automatic batching
2. **Multi-tenant Schema**: Demonstrates `@scope` directive for tenant isolation
3. **Type-safe GlobalIDs**: Uses `@idOf` with encoded GlobalID system
4. **Complex Relationships**: Shows related entities with efficient resolution
5. **List Support**: Implements simple GraphQL lists with optional limit parameter
6. **Input Validation**: Demonstrates `@oneOf` for exactly-one-field semantics
7. **Variables and Variable Providers**: Dynamic field selection with three different approaches
8. **Fragment Optimization**: Specifies exact field requirements for performance
9. **Comprehensive Documentation**: All directives and features are thoroughly documented

## Batch Resolver Examples

### Simple Batch Counting
```kotlin
// Efficiently count films for multiple characters
@Resolver(objectValueFragment = "fragment _ on Character { id }")
class CharacterFilmCountResolver : CharacterResolvers.FilmCount() {
  override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Int>> {
    val counts = batchCountFilms(contexts.map { it.objectValue.getId().internalID })
    return contexts.map { FieldValue.ofValue(counts[it.objectValue.getId().internalID] ?: 0) }
  }
}
```

### Complex Multi-Source Batching
```kotlin
// Combine data from multiple sources efficiently
@Resolver(objectValueFragment = "fragment _ on Character { id name birthYear }")
class CharacterRichSummaryResolver : CharacterResolvers.RichSummary() {
  override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
    // Batch lookup homeworlds, species, film counts
    val summaries = createRichSummaries(contexts)
    return contexts.map { FieldValue.ofValue(summaries[it]) }
  }
}
```

## Variables and Variable Providers Examples
```graphql
# Variables with @Variable fromArgument
query BasicProfile {
  node(id: "Q2hhcmFjdGVyOjE=") {  # Luke Skywalker
    ... on Character {
      name
      characterProfile(includeDetails: false)
      # Result: "Character Profile: Luke Skywalker (basic info only)"
    }
  }
}

query DetailedProfile {
  node(id: "Q2hhcmFjdGVyOjE=") {
    ... on Character {
      name
      characterProfile(includeDetails: true)
      # Result: "Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg"
    }
  }
}

# VariableProvider with dynamic computation
query CharacterStats {
  node(id: "Q2hhcmFjdGVyOjU=") {  # Obi-Wan Kenobi
    ... on Character {
      name
      characterStats(minAge: 25, maxAge: 100)
      # Result: "Stats for Obi-Wan Kenobi (Age range: 25-100), Born: 57BBY, Height: 182cm, Species: Human"
    }
  }
}

# Argument-based conditional logic
query FormattedDescriptions {
  node(id: "Q2hhcmFjdGVyOjI=") {  # Princess Leia
    ... on Character {
      name
      detailed: formattedDescription(format: "detailed")
      # Result: "Princess Leia (born 19BBY) - brown eyes, brown hair"

      yearOnly: formattedDescription(format: "year-only")
      # Result: "Princess Leia (born 19BBY)"

      default: formattedDescription(format: "default")
      # Result: "Princess Leia"
    }
  }
}

# Combined usage of all three approaches
query CombinedVariablesDemo {
  node(id: "Q2hhcmFjdGVyOjE=") {  # Luke Skywalker
    ... on Character {
      name

      # @Variable with fromArgument examples
      basicProfile: characterProfile(includeDetails: false)
      detailedProfile: characterProfile(includeDetails: true)

      # VariableProvider with dynamic computation
      youngStats: characterStats(minAge: 0, maxAge: 30)
      oldStats: characterStats(minAge: 30, maxAge: 100)

      # Argument-based conditional logic
      nameOnly: formattedDescription(format: "default")
      yearOnly: formattedDescription(format: "year-only")
      detailed: formattedDescription(format: "detailed")
    }
  }
}
```

### Film Fragment Examples
```graphql
query {
  allFilms(limit: 2) {
    # Standard fields
    title
    director

    # Shorthand fragment - delegates to title
    displayTitle

    # Full fragment - combines episode, title, director
    summary

    # Full fragment - production details
    productionDetails

    # Full fragment with character data
    characterCountSummary
  }
}
```

## Testing

The demo includes comprehensive tests:
- **Integration tests** for all resolvers
- **Batch resolver performance tests**
- **Scope isolation tests**
- **GlobalID encoding/decoding tests**
- **@oneOf input validation tests**

Key test files:
- `ResolverIntegrationTest.kt` - Tests all standard resolvers
- `BatchResolverDemoTest.kt` - Tests batch resolver efficiency
- `ExtrasScopeTest.kt` - Tests multi-tenant scoping
- `GlobalIDDemoTest.kt` - Tests GlobalID functionality

The implementation demonstrates how Viaduct's custom directives and batch resolvers enable powerful GraphQL schema capabilities while maintaining optimal performance and clean, understandable code organization.

All examples shown in this document are representations of actual working code in this demo application. More example queries and usage patterns can be found in the [integration tests](src/test/kotlin/viaduct/demoapp/starwars/ResolverIntegrationTest.kt).
