package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Example of full fragment syntax for complex computed fields
 *
 * @resolver("fragment _ on Character { name eyeColor hairColor }"): Fragment definition to
 *          fetch multiple appearance-related fields to create a description
 */
@Resolver(
    """
    fragment _ on Character {
        name
        eyeColor
        hairColor
    }
    """
)
class CharacterAppearanceDescriptionResolver
    @Inject
    constructor() : CharacterResolvers.AppearanceDescription() {
        override suspend fun resolve(ctx: Context): String? {
            // Gets the character from the context with the data specified in the @Resolver
            val character = ctx.objectValue

            // Builds a description using the fetched fields, providing defaults if any are missing
            val name = character.getName() ?: "Someone"
            val eyeColor = character.getEyeColor() ?: "unknown eyes"
            val hairColor = character.getHairColor() ?: "unknown hair"

            return "$name has $eyeColor eyes and $hairColor hair"
        }
    }
