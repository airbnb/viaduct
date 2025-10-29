package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver
import viaduct.api.Variable

/**
 * **Variables with `@Variable` and `fromArgument`** parameter in Viaduct.
 *
 * This resolver showcases how to use the `@Variable` annotation with `fromArgument` to bind
 * GraphQL resolver arguments to variables that control field selection at the GraphQL execution level.
 *
 * ## Key Components:
 *
 * 1. **`@Variable` Annotation**:
 *    ```kotlin
 *    variables = [Variable("includeDetails", fromArgument = "includeDetails")]
 *    ```
 *
 *    - Binds the resolver's `includeDetails` argument to a GraphQL variable `$includeDetails`
 *    - The variable value is taken directly from the argument value at runtime
 *
 * 2. **GraphQL Fragment with Directives**:
 *    ```graphql
 *    fragment _ on Character {
 *        name
 *        birthYear @include(if: $includeDetails)
 *        height @include(if: $includeDetails)
 *        mass @include(if: $includeDetails)
 *    }
 *    ```
 *    - Uses `@include(if: $includeDetails)` to conditionally select fields
 *    - When `includeDetails=true`: all fields are selected and available to the resolver
 *    - When `includeDetails=false`: only `name` is selected, other fields throw exceptions if accessed
 *
 * ## Benefits:
 *
 * - **GraphQL-level Optimization**: Fields not needed are never fetched from data sources
 * - **Declarative**: Field selection logic expressed in GraphQL fragments
 * - **Efficient**: Reduces over-fetching and improves performance
 * - **Type-Safe**: GraphQL engine enforces variable types and field availability
 *
 * ### Usage Examples:
 *
 * ```graphql
 * query {
 *   person(id: "cGVvcGxlOjE=") {
 *     characterProfile(includeDetails: false)
 *   }
 * }
 * ```
 * Result: "Character Profile: Luke Skywalker (basic info only)"
 *
 * @see Variable
 * @see CharacterStatsResolver for VariableProvider example
 * @see CharacterFormattedDescriptionResolver for argument-based alternative
 */
// tag::resolver_example[18] Example of using @Variable with fromArgument to control field selection
@Resolver(
    """
    fragment _ on Character {
        name
        birthYear @include(if: ${'$'}includeDetails)
        height @include(if: ${'$'}includeDetails)
        mass @include(if: ${'$'}includeDetails)
    }
    """,
    variables = [Variable("includeDetails", fromArgument = "includeDetails")]
)
class ProfileFieldResolver
    @Inject
    constructor() : CharacterResolvers.CharacterProfile() {
        override suspend fun resolve(ctx: Context): String? {
            val character = ctx.objectValue
            val name = character.getName() ?: "Unknown"

            return try {
                // If includeDetails is true, these fields will be available
                val birthYear = character.getBirthYear()
                val height = character.getHeight()
                val mass = character.getMass()

                buildString {
                    append("Character Profile: $name")
                    birthYear?.let { append(", Born: $it") }
                    height?.let { append(", Height: ${it}cm") }
                    mass?.let { append(", Mass: ${it}kg") }
                }
            } catch (e: Exception) {
                // If includeDetails is false, detailed fields won't be available
                "Character Profile: $name (basic info only)"
            }
        }
    }
