package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Demonstrates shorthand fragment syntax - specifies exactly which fields to fetch
 *
 * @resolver("fragment _ on Character { name birthYear }"): Full fragment syntax that specifies
 *          exactly which fields should be fetched from the Character object. This enables
 *          computed fields that depend on multiple other fields.
 */
@Resolver(
    """
        name
        birthYear
    """
)
class CharacterDisplaySummaryResolver
    @Inject
    constructor() : CharacterResolvers.DisplaySummary() {
        override suspend fun resolve(ctx: Context): String? {
            val character = ctx.objectValue

            // Builds a summary using the fetched fields, those are provided by the @Resolver annotation above
            val name = character.getName() ?: "Unknown"
            val birthYear = character.getBirthYear() ?: "Unknown birth year"

            return "$name ($birthYear)"
        }
    }
