---
title: Mutations
description: Implementing mutation operations in the Star Wars demo app using Viaduct.
layout: docs/single
weight: 5
---

The Star Wars demo app includes several mutation operations that allow you to modify data. All mutations are available
under the `Mutation` root type and demonstrate how to implement data modification operations in Viaduct.

## Mutation implementation patterns

Mutations in Viaduct follow similar patterns to queries but focus on data modification operations. Each mutation
resolver typically:

1. **Validates input data** using input types with appropriate constraints.
2. **Performs the data modification** on the underlying data store.
3. **Returns updated entities** that can be further resolved with additional fields.
4. **Maintains data consistency** and referential integrity.

## Available mutations

### Create a new character

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="input_example" lang="graphql">}}

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="mutation_example" lang="graphql">}}

Implementation:

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/CreateCharacterMutation.kt" tag="create_example">}}

Execution :

```graphql
mutation {
  createCharacter(input: {
    name: "New Jedi"
    birthYear: "19BBY"
    eyeColor: "blue"
    gender: "male"
    hairColor: "brown"
    height: 180
    mass: 75.5
    homeworldId: "UGxhbmV0OjE="  # Tatooine
    speciesId: "U3BlY2llczox"    # Human
  }) {
    id
    name
    birthYear
    homeworld { name }
    species { name }
  }
}
```

**Implementation notes:**

- Uses input types for structured data validation.
- Generates new GlobalIDs for created entities.
- Supports relationship creation via reference IDs.
- Returns the full created entity for immediate use.

### Update character name

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="mutation_example_2" lang="graphql">}}

**Implementation notes:**

{{< codetag path="demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/UpdateCharacterNameMutation.kt" tag="update-character-name-resolver">}}

- Uses GlobalIDs for entity identification.
- Performs atomic field updates.
- Returns updated entity for verification.

### Add character to film

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Film.graphqls" tag="mutation_example_3" lang="graphql">}}

```graphql
mutation {
  addCharacterToFilm(input: {
    filmId: "RmlsbTox"           # A New Hope
    characterId: "Q2hhcmFjdGVyOjU="  # Obi-Wan Kenobi
  }) {
    character {
      name
    }
    film {
      title
    }
  }
}
```

**Implementation notes:**

- Manages many-to-many relationships.
- Uses input types for relationship data.
- Returns both related entities for verification.
- Maintains bidirectional relationship consistency.

### Delete character

{{< codetag path="demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls" tag="mutation_example_4" lang="graphql">}}

**Implementation notes:**

- Uses GlobalIDs for entity identification.
- Returns boolean success indicator.
- Handles cascading relationship cleanup.
- Maintains data integrity during deletion.

## Mutation best practices

1. **Use input types:** structure mutation arguments with dedicated input types for validation and clarity.
2. **GlobalID consistency:** always use encoded GlobalIDs for entity references.
3. **Return useful data:** return updated entities or relationship objects, not just success flags.
4. **Validate relationships:** ensure referenced entities exist before creating relationships.
5. **Handle errors gracefully:** provide meaningful error messages for invalid operations.
6. **Maintain consistency:** update all related data structures atomically.

**Note:** when using mutations, make sure to use properly encoded GlobalIDs.

{{< prevnext >}}
