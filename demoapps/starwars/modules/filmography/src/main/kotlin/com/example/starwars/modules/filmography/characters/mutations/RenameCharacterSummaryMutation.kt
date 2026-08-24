package com.example.starwars.modules.filmography.characters.mutations

import com.example.starwars.filmography.resolverbases.MutationResolvers
import com.example.starwars.modules.filmography.characters.operations.RenameCharacterOperation
import io.micronaut.context.annotation.Prototype
import viaduct.api.resolver.Resolver

/**
 * Runs the statically-declared [RenameCharacterOperation] mutation as a submutation via
 * `ctx.mutation(operation, variables)`.
 *
 * `ctx.mutation` is only available in a Mutation-rooted resolver context, so this resolver backs a
 * `Mutation` field. It forwards the `id` and `name` arguments as variables; because the submutation
 * shares the parent request's state, the `updateCharacterName` resolver it delegates to still sees
 * the same admin security context. The typed mutation GRT it returns exposes the identity fields
 * selected via the shared `CharacterIdentityFields` fragment.
 */
// tag::mutation_operation_consumer[17] Executing a @GraphQLOperation with ctx.mutation
@Resolver
@Prototype
class RenameCharacterSummaryMutation : MutationResolvers.RenameCharacterSummary() {
    override suspend fun resolve(ctx: Context): String? {
        val result = ctx.mutation(
            RenameCharacterOperation,
            mapOf(
                "id" to ctx.arguments.id,
                "name" to ctx.arguments.name
            )
        )
        val character = result.getUpdateCharacterNameOrThrow() ?: return null

        val name = character.getNameOrThrow() ?: "Unknown"
        val birthYear = character.getBirthYearOrThrow() ?: "Unknown birth year"
        return "$name ($birthYear)"
    }
}
