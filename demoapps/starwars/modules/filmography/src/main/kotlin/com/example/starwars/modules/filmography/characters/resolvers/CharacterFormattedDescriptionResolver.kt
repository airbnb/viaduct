package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import viaduct.api.resolver.Resolver

/**
 * **Argument-Based Conditional Logic** example as an alternative to Variables in Viaduct.
 *
 * This resolver showcases the traditional approach to conditional field resolution using
 * standard argument processing within the resolver, rather than Variables or VariableProvider.
 *
 * ## Key Components:
 *
 * 1. **Multiple named fragment spreads**: a single selection set can spread more than one named
 *    fragment. This resolver needs all four fields, so it composes two reusable fragments —
 *    `CharacterIdentityFields` (`name`, `birthYear`) and `CharacterAppearanceFields`
 *    (`eyeColor`, `hairColor`) — instead of listing the fields inline:
 *
 *    ```graphql
 *    fragment _ on Character {
 *        ...CharacterIdentityFields
 *        ...CharacterAppearanceFields
 *    }
 *    ```
 *
 * 2. ctx.arguments.format optional argument : Determines the formatting style of the description.
 *
 * ## When to Use Argument-Based Logic:
 *
 * This approach is ideal when:
 * - **Simple Logic**: Straightforward conditional formatting or display
 * - **All Data Needed**: All fields are typically required regardless of arguments
 * - **Rapid Development**: Quick implementation without GraphQL optimization concerns
 * - **Legacy Migration**: Transitioning from older resolver patterns
 * - **Output Formatting**: Different presentation of the same data set
 *
 * ## Usage Examples:
 *
 * ```graphql
 * query DetailedFormat {
 *   person(id: "cGVvcGxlOjU=") {
 *     formattedDescription(format: "detailed")
 *   }
 * }
 * # Result: "Princess Leia (born 19BBY) - brown eyes, brown hair"
 *
 * @see ProfileResolver for @Variable fromArgument example
 * @see StatsResolver for VariableProvider example
 */
// tag::resolver_example[60] Example of argument-based conditional logic for formatted description
@Resolver(
    """
    fragment _ on Character {
        ...CharacterIdentityFields
        ...CharacterAppearanceFields
    }
    """
)
class CharacterFormattedDescriptionResolver : CharacterResolvers.FormattedDescription() {
    override suspend fun resolve(ctx: Context): String? {
        val character = ctx.getObjectValue()
        val name = character.getName() ?: "Unknown"
        val format = ctx.arguments.format

        return when (format) {
            "detailed" -> {
                val birthYear = character.getBirthYear()
                val eyeColor = character.getEyeColor()
                val hairColor = character.getHairColor()

                buildString {
                    append(name)
                    birthYear?.let { append(" (born $it)") }
                    if (eyeColor != null || hairColor != null) {
                        append(" - ")
                        eyeColor?.let { append("$it eyes") }
                        if (eyeColor != null && hairColor != null) append(", ")
                        hairColor?.let { append("$it hair") }
                    }
                }
            }

            "year-only" -> {
                val birthYear = character.getBirthYear()
                birthYear?.let { "$name (born $it)" } ?: "$name (birth year unknown)"
            }

            "appearance-only" -> {
                val eyeColor = character.getEyeColor()
                val hairColor = character.getHairColor()

                buildString {
                    append(name)
                    if (eyeColor != null || hairColor != null) {
                        append(" - ")
                        eyeColor?.let { append("$it eyes") }
                        if (eyeColor != null && hairColor != null) append(", ")
                        hairColor?.let { append("$it hair") }
                    }
                }
            }

            else -> name // default format - just name
        }
    }
}
