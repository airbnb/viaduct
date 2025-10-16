---
title: Variables and Variable Providers
description: Using Variables and Variable Providers for dynamic field selection in Viaduct.
weight: 3
---

**Purpose**: Enable dynamic field selection and conditional GraphQL queries through runtime variable computation.

**Usage**: Variables can be bound to resolver arguments or computed dynamically using VariableProvider classes to control which fields are selected at the GraphQL execution level.

Viaduct supports three approaches for dynamic field resolution:

## 1. Variables with @Variable and fromArgument

Variables can be bound directly to resolver arguments to control GraphQL directive evaluation:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/ProfileFieldResolver.kt" tag="resolver_example">}}

**Benefits**: GraphQL-level optimization, declarative field selection, efficient data fetching.

## 2. Argument-Based Statistics Logic
For practical demo purposes, the character stats use argument-based conditional logic:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterStatsResolver.kt" tag="resolver_example">}}

**Benefits**: Simple implementation, full access to all fields, easy to debug and maintain.

*Note: The full VariableProvider API with dynamic computation is available in the complete Viaduct runtime but simplified here for demo clarity.*

## 3. Argument-Based Conditional Logic
For simpler cases, traditional argument processing within resolvers:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/resolvers/CharacterFormattedDescriptionResolver.kt" tag="resolver_example">}}

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

## Query Examples

### @Variable fromArgument

```graphql
query BasicProfile {
  node(id: "Q2hhcmFjdGVyOjE=") {  # Luke Skywalker
    ... on Character {
      name
      characterProfile(includeDetails: false)
      # Result: "Character Profile: Luke Skywalker (basic info only)"
    }
  }
}
```

Include details example

```graphql
query DetailedProfile {
  node(id: "Q2hhcmFjdGVyOjE=") {
    ... on Character {
      name
      characterProfile(includeDetails: true)
      # Result: "Character Profile: Luke Skywalker, Born: 19BBY, Height: 172cm, Mass: 77.0kg"
    }
  }
}
```

### VariableProvider with dynamic computation
```graphql
query CharacterStats {
  node(id: "Q2hhcmFjdGVyOjU=") {  # Obi-Wan Kenobi
    ... on Character {
      name
      characterStats(minAge: 25, maxAge: 100)
      # Result: "Stats for Obi-Wan Kenobi (Age range: 25-100), Born: 57BBY, Height: 182cm, Species: Human"
    }
  }
}

```

### Argument-based conditional logic
```graphql
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
```

### Combined usage of all three approaches
```graphql
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
