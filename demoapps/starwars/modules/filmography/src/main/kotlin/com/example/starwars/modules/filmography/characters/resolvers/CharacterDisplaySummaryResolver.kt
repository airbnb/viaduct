package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import viaduct.api.resolver.Resolver

/**
 * Demonstrates spreading a named fragment (`CharacterIdentityFieldsFragment`) in an
 * `objectValueFragment`.
 *
 * Rather than repeating `name birthYear` inline, this resolver spreads the shared
 * `CharacterIdentityFields` fragment with `...CharacterIdentityFields`. The same fragment is reused
 * by `CharacterRichSummaryResolver`.
 */
// tag::named_fragment_consumer[5] Spreading a named fragment in objectValueFragment
@Resolver("fragment _ on Character { ...CharacterIdentityFields }")
class CharacterDisplaySummaryResolver : CharacterResolvers.DisplaySummary() {
    override suspend fun resolve(ctx: Context): String? {
        val character = ctx.getObjectValue()

        // Builds a summary using the fetched fields, those are provided by the @Resolver annotation above
        val name = character.getName() ?: "Unknown"
        val birthYear = character.getBirthYear() ?: "Unknown birth year"

        return "$name ($birthYear)"
    }
}
