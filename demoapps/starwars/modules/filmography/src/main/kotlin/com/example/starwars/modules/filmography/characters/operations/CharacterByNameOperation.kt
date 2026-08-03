package com.example.starwars.modules.filmography.characters.operations

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation

/**
 * A statically-declared GraphQL **query** operation.
 *
 * `@GraphQLOperation` declares an executable GraphQL document **once** on a Kotlin singleton
 * `object` that extends [QueryFromAnnotation]. The document is validated against the schema at
 * build time and can then be executed from any resolver in this tenant module with
 * `ctx.query(CharacterByNameOperation, variables)` — no need to build the selection string inline.
 *
 * This operation calls the existing `searchCharacter` query with a `$name` variable and spreads the
 * external named fragment `CharacterIdentityFields` (declared in `CharacterIdentityFieldsFragment`),
 * showing that operations compose with `@GraphQLFragment` the same way inline selections do.
 */
// tag::query_operation_example[10] Declaring a @GraphQLOperation query
@GraphQLOperation(
    """
    query(${'$'}name: String!) {
      searchCharacter(search: { byName: ${'$'}name }) {
        ...CharacterIdentityFields
      }
    }
    """
)
object CharacterByNameOperation : QueryFromAnnotation()
