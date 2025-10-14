package viaduct.demoapp.characters.viaduct.fieldresolvers

import viaduct.api.Resolver
import viaduct.demoapp.filmography.resolverbases.CharacterResolvers

/**
 * Example of a computed field resolver in the Character type.
 *
 * This resolver computes whether a character is considered an adult based on their birth year.
 *
 * @resolver("birthYear"): Fragment syntax for accessing the birthYear field
 */
// tag::resolver_example[15] Example of a computed field resolver
@Resolver(
    """
    fragment _ on Character {
        birthYear
    }
    """
)
class IsAdultResolver : CharacterResolvers.IsAdult() {
    override suspend fun resolve(ctx: Context): Boolean? {
        // Example rule: consider adults those older than 21 years
        return ctx.objectValue.getBirthYear()?.let {
            age(it) > 21
        } ?: false
    }

    private fun age(value: String): Double {
        return value.dropLast(3).toDouble()
    }
}
