@file:OptIn(ExperimentalApi::class)

package com.example.starwars.modules.filmography.characters.operations

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.apiannotations.ExperimentalApi

/**
 * A statically-declared GraphQL **mutation** operation.
 *
 * Just like [CharacterByNameOperation] but for the write path: the object extends
 * [MutationFromAnnotation] and its document is a `mutation`. It is executed with
 * `ctx.mutation(RenameCharacterOperation, variables)` from a Mutation-rooted resolver.
 *
 * The operation delegates to the existing `updateCharacterName` mutation, passing the character's
 * `$id` and new `$name` as variables and selecting back the identity fields via the shared
 * `CharacterIdentityFields` fragment.
 */
// tag::mutation_operation_example[10] Declaring a @GraphQLOperation mutation
@GraphQLOperation(
    """
    mutation(${'$'}id: ID!, ${'$'}name: String!) {
      updateCharacterName(id: ${'$'}id, name: ${'$'}name) {
        ...CharacterIdentityFields
      }
    }
    """
)
object RenameCharacterOperation : MutationFromAnnotation()
