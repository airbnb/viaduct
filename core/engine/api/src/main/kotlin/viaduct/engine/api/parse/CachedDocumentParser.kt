package viaduct.engine.api.parse

import com.github.benmanes.caffeine.cache.Caffeine
import graphql.language.Document

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
