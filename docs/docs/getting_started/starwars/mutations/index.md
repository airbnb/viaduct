---
title: Mutations
description: Implementing mutation operations in the Star Wars demo app using Viaduct.
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


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "input_example", lang="kotlin") }}



{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "mutation_example", lang="kotlin") }}


Implementation:


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/CreateCharacterMutation.kt", "create_example", lang="kotlin") }}


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
- **Validates security access** using request-scoped context (see [Request Context](../requestcontext/index.md) for details).

### Update character name


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "mutation_example_2", lang="kotlin") }}


**Implementation notes:**


{{ codetag("demoapps/starwars/modules/filmography/src/main/kotlin/com/example/starwars/modules/filmography/characters/mutations/UpdateCharacterNameMutation.kt", "update-character-name-resolver", lang="kotlin") }}


- Uses GlobalIDs for entity identification.
- Performs atomic field updates.
- Returns updated entity for verification.

### Add character to film


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Film.graphqls", "mutation_example_3", lang="kotlin") }}


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


{{ codetag("demoapps/starwars/modules/filmography/src/main/viaduct/schema/Character.graphqls", "mutation_example_4", lang="kotlin") }}


**Implementation notes:**

- Uses GlobalIDs for entity identification.
- Returns boolean success indicator.
- Handles cascading relationship cleanup.
- Maintains data integrity during deletion.

## Using request context

Operations often need access to request-specific data like authentication, authorization, or tenant information. While this example focuses on mutations, **request context is available to all operations** — queries, mutations, and subscriptions may all use request context as needed.

Viaduct fully supports request context management through your framework's dependency injection. For detailed information on different approaches and best practices, see the [Request Context](../requestcontext/index.md) documentation.

## Mutation best practices

1. **Use input types:** structure mutation arguments with dedicated input types for validation and clarity.
2. **GlobalID consistency:** always use encoded GlobalIDs for entity references.
3. **Return useful data:** return updated entities or relationship objects, not just success flags.
4. **Validate relationships:** ensure referenced entities exist before creating relationships.
5. **Handle errors gracefully:** provide meaningful error messages for invalid operations.
6. **Maintain consistency:** update all related data structures atomically.
7. **Leverage request context:** use framework-provided request scoping for authentication, authorization, and tenant isolation.
8. **Inject dependencies:** prefer dependency injection over global state or manual context threading.

**Note:** when using mutations, make sure to use properly encoded GlobalIDs.

> For the full mutation resolver API and generated base-class reference, see the [Mutations developer reference](../../../docs/developers/resolvers/mutations.md).
