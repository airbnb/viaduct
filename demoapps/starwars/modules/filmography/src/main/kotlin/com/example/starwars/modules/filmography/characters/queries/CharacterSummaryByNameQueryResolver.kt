@file:OptIn(ExperimentalApi::class)

package com.example.starwars.modules.filmography.characters.queries

import com.example.starwars.filmography.resolverbases.QueryResolvers
import com.example.starwars.modules.filmography.characters.operations.CharacterByNameOperation
import viaduct.api.resolver.Resolver
import viaduct.apiannotations.ExperimentalApi

/**
 * Runs the statically-declared [CharacterByNameOperation] query as a subquery via
 * `ctx.query(operation, variables)`.
 *
 * Instead of assembling the selection string inline at the call site, this resolver hands
 * `ctx.query` the `@GraphQLOperation` object. Viaduct inlines the external `CharacterIdentityFields`
 * fragment the operation spreads and executes it against the root `Query` type, returning a typed
 * Query GRT whose getters mirror the schema (`getSearchCharacter()` → `getName()` / `getBirthYear()`).
 */
// tag::query_operation_consumer[11] Executing a @GraphQLOperation with ctx.query
@Resolver
class CharacterSummaryByNameQueryResolver : QueryResolvers.CharacterSummaryByName() {
    override suspend fun resolve(ctx: Context): String? {
        val result = ctx.query(CharacterByNameOperation, mapOf("name" to ctx.arguments.name))
        val character = result.getSearchCharacter() ?: return null

        val name = character.getName() ?: "Unknown"
        val birthYear = character.getBirthYear() ?: "Unknown birth year"
        return "$name ($birthYear)"
    }
}
