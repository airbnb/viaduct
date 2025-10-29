package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * **Argument-Based Conditional Logic** example as an alternative to Variables in Viaduct.
 *
 * This resolver showcases the traditional approach to conditional field resolution using
 * standard argument processing within the resolver, rather than Variables or VariableProvider.
 *
 * ## Key Components:
 *
 * 1. **Standard GraphQL Fragment**: A fragment that fetches all potentially needed fields.
 *
 *    ```graphql
 *    fragment _ on Character {
 *        name
 *        birthYear
 *        eyeColor
 *        hairColor
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
// tag::resolver_example[19] Example of argument-based conditional logic for formatted description
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear
        eyeColor
        hairColor
    }
    """
)
class CharacterFormattedDescriptionResolver
    @Inject
    constructor() : CharacterResolvers.FormattedDescription() {
        override suspend fun resolve(ctx: Context): String? {
            val character = ctx.objectValue
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
