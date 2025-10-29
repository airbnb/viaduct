package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import jakarta.inject.Inject
import viaduct.api.Resolver

/**
 * Shorthand fragment syntax example - delegates to the title field
 *
 * @resolver("title"): Shorthand fragment syntax that delegates resolution to another field.
 *                   This resolver will automatically fetch the "title" field and return its value.
 */
@Resolver("title")
class FilmDisplayTitleResolver
    @Inject
    constructor() : FilmResolvers.DisplayTitle() {
        override suspend fun resolve(ctx: Context): String? {
            // Access the source Film from the context
            return ctx.objectValue.getTitle()
        }
    }
