package viaduct.demoapp.starwars.resolvers

import viaduct.api.Resolver

/**
 * Film resolvers - for all fields that have @resolver directives.
 * These must extend the generated base classes to work with the codegen system.
 * Simple scalar properties without @resolver directives are handled automatically.
 */

/**
 * Resolver for the openingCrawl field - large data field that requires custom resolution.
 * Updated to extend generated base class.
 */
@Resolver
class FilmOpeningCrawlResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.OpeningCrawl() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        return ctx.objectValue.getOpeningCrawl()
    }
}

/**
 * Demonstrates shorthand fragment syntax - delegates to title field
 * @resolver("title"): Shorthand fragment syntax for simple field delegation
 * Updated to extend generated base class.
 */
@Resolver("title")
class FilmDisplayTitleResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.DisplayTitle() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        return ctx.objectValue.getTitle()
    }
}

/**
 * Demonstrates shorthand fragment syntax - combines title, episodeID, and director
 * @resolver("title episodeID director"): Shorthand syntax accessing multiple fields
 * Updated to extend generated base class.
 */
@Resolver("title episodeID director")
class FilmSummaryResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.Summary() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.objectValue
        return "Episode ${film.getEpisodeID()}: ${film.getTitle()} (Directed by ${film.getDirector()})"
    }
}

/**
 * Another full fragment example - creates a detailed description
 * @resolver("fragment _ on Film { title director producers releaseDate }"):
 *          Fragment syntax fetching production details to create a detailed description
 * Updated to extend generated base class.
 */
@Resolver(
    """
    fragment _ on Film {
        title
        director
        producers
        releaseDate
    }
    """
)
class FilmProductionDetailsResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.ProductionDetails() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.objectValue
        val producerList = film.getProducers()?.filterNotNull()?.joinToString(", ") ?: "Unknown producers"
        return "${film.getTitle()} was released on ${film.getReleaseDate()}, directed by ${film.getDirector()} and produced by $producerList"
    }
}

/**
 * Complex fragment example showing relationship data
 * @resolver("fragment _ on Film { title characters }"): Fragment syntax
 *          that includes both simple fields and list fields
 * Updated to extend generated base class.
 */
@Resolver(
    """
    fragment _ on Film {
        id
        title
        characters(limit: 10) {
            id
        }
    }
    """
)
class FilmCharacterCountSummaryResolver : viaduct.demoapp.starwars.resolverbases.FilmResolvers.CharacterCountSummary() {
    override suspend fun resolve(ctx: Context): String? {
        // Access the source Film from the context
        val film = ctx.objectValue
        // We can now access the characters list directly from the fragment
        val characters = film.getCharacters()
        val characterCount = characters?.size ?: 0
        return "${film.getTitle()} features $characterCount main characters"
    }
}
