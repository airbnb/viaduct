package viaduct.engine.api.parse

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.language.Document

/**
 * Singleton that caches parsed GraphQL [Document] instances by their source string.
 *
 * Parsing is CPU-intensive, and the same fragment / operation strings are typically parsed many
 * times during the lifetime of a process (e.g. for every request that uses a fixed fragment).
 * This cache uses a bounded Caffeine LRU cache (up to 10 000 entries) so that repeated calls
 * to [parseDocument] with the same input return the previously computed [Document] without
 * re-parsing. Use [addToCache] to pre-populate the cache with documents that were parsed
 * through other code paths.
 */
object CachedDocumentParser {
    private val cache =
        Caffeine.newBuilder()
            .maximumSize(10000)
            .build<String, Document>()
            .asMap()

    fun parseDocument(document: String): Document {
        return cache.getOrPut(document) { DocumentParser.parse(document) }
    }

    fun addToCache(
        documentString: String,
        document: Document
    ) {
        cache.putIfAbsent(documentString, document)
    }
}
