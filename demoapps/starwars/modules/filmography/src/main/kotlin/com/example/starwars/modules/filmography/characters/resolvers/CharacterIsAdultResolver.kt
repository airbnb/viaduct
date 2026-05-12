package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import viaduct.api.resolver.Resolver

/**
 * Example of a computed field resolver in the Character type.
 *
 * This resolver computes whether a character is considered an adult based on their birth year.
 *
 * @resolver("birthYear"): Fragment syntax for accessing the birthYear field
 */
// tag::resolver_example[21] Example of a computed field resolver
@Resolver(
    """
    fragment _ on Character {
        birthYear
    }
    """
)
class CharacterIsAdultResolver : CharacterResolvers.IsAdult() {
    override suspend fun resolve(ctx: Context): Boolean? {
        // Example rule: consider adults those older than 21 years
        return ctx.getObjectValue().getBirthYear()?.let {
            age(it) > 21
        } ?: false
    }

    private fun age(value: String): Double {
        return value.dropLast(3).toDouble()
    }
}
