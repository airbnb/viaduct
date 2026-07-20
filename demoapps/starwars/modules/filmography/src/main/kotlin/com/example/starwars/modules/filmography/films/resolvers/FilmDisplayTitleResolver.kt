package com.example.starwars.modules.filmography.films.resolvers

import com.example.starwars.filmography.resolverbases.FilmResolvers
import io.micronaut.context.annotation.Prototype
import viaduct.api.resolver.Resolver

/**
 * Shorthand fragment syntax example - delegates to the title field
 *
 * @resolver("title"): Shorthand fragment syntax that delegates resolution to another field.
 *                   This resolver will automatically fetch the "title" field and return its value.
 */
@Resolver("title")
@Prototype
class FilmDisplayTitleResolver : FilmResolvers.DisplayTitle() {
    override suspend fun resolve(ctx: Context) = ctx.getObjectValue().getTitle()
}
