package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Demonstrates **Argument-Based Conditional Logic for Statistics** in Viaduct.
 *
 * This resolver demonstrates a practical approach for generating character statistics
 * based on age range parameters using standard argument-based logic. While Viaduct
 * supports more advanced VariableProvider patterns, this shows a straightforward
 * implementation suitable for the demo environment.
 *
 * ## Benefits:
 * - **Simplicity**: Easy to understand and implement
 * - **Flexibility**: Full Kotlin language features available
 * - **Maintainability**: Logic is contained within the resolver
 *
 * @see ProfileFieldResolver for @Variable fromArgument example
 * @see CharacterFormattedDescriptionResolver for more argument-based patterns
 */
// tag::resolver_example[22] Example of argument-based conditional logic for statistics
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear
        height
        species {
            name
        }
    }
    """
)
class CharacterStatsResolver
    @Inject
    constructor() : CharacterResolvers.CharacterStats() {
        override suspend fun resolve(ctx: Context): String? {
            val character = ctx.objectValue
            val name = character.getName() ?: "Unknown"
            val args = ctx.arguments

            return try {
                buildString {
                    append("Stats for $name")
                    append(" (Age range: ${args.minAge}-${args.maxAge})")

                    // Try to access conditional fields
                    try {
                        val birthYear = character.getBirthYear()
                        val height = character.getHeight()
                        birthYear?.let { append(", Born: $it") }
                        height?.let { append(", Height: ${it}cm") }
                    } catch (e: Exception) {
                        append(" - age details not available for this range")
                    }

                    try {
                        val species = character.getSpecies()
                        species?.getName()?.let { append(", Species: $it") }
                    } catch (e: Exception) {
                        // Species not included for this age range
                    }
                }
            } catch (e: Exception) {
                "Stats unavailable for $name"
            }
        }
    }
