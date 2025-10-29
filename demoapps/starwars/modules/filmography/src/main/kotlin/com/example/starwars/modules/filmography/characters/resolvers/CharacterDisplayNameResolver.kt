package com.example.starwars.modules.filmography.characters.resolvers

import com.example.starwars.filmography.resolverbases.CharacterResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Shorthand fragment syntax example - delegates to the name field
 *
 * @resolver("name"): Shorthand fragment syntax that delegates resolution to another field.
 *                   This resolver will automatically fetch the "name" field and return its value.
 */
// tag::resolver_example[8] Example of a simple resolver
@Resolver("name")
class CharacterDisplayNameResolver
    @Inject
    constructor() : CharacterResolvers.DisplayName() {
        override suspend fun resolve(ctx: Context): String? {
            // Directly returns the name of the character from the context. The "name" field is
            // automatically fetched due to the @Resolver annotation.
            return ctx.objectValue.getName()
        }
    }
