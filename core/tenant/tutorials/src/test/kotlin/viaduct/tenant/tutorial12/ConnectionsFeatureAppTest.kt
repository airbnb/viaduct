@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial12

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.connection.OffsetCursor
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial12.resolverbases.NodeResolvers
import viaduct.tenant.tutorial12.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Build paginated GraphQL APIs using Relay-style Connections
 * - Choose between the three ConnectionBuilder strategies
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - @connection and @edge directives
 * - ConnectionBuilder.fromList() - hand over the full dataset, framework paginates
 * - ConnectionBuilder.fromEdges() - build edges manually to carry extra per-edge data
 * - ConnectionBuilder.fromSlice() wrapping a legacy offset/limit backend
 *
 * CONCEPTS COVERED:
 * - Relay Connection spec: edges, cursors, PageInfo
 * - OffsetCursor encoding
 * - Forward (first/after) and backward (last/before) pagination
 *
 * PREVIOUS: [viaduct.tenant.tutorial11.SimpleSubqueriesFeatureAppTest]
 */
class ConnectionsFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        |
        | enum Genre { FICTION NON_FICTION SCIENCE }
        |
        | type Book implements Node @resolver {
        |   id: ID!
        |   title: String!
        |   genre: Genre!
        |   year: Int!
        | }
        |
        | type BookEdge @edge {
        |   node: Book
        |   cursor: String!
        |   reason: String
        | }
        |
        | type BookConnection @connection {
        |   edges: [BookEdge!]!
        |   pageInfo: PageInfo!
        | }
        |
        | extend type Query {
        |   books(first: Int, after: String, last: Int, before: String): BookConnection! @resolver
        |   booksByGenre(genre: Genre!, first: Int, after: String): BookConnection! @resolver
        |   highlightedBooks(first: Int, after: String): BookConnection! @resolver
        | }
        |
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        data class BookData(val id: String, val title: String, val genre: String, val year: Int)

        val ALL_BOOKS = listOf(
            BookData("b1", "Dune", "FICTION", 1965),
            BookData("b2", "The Selfish Gene", "SCIENCE", 1976),
            BookData("b3", "Foundation", "FICTION", 1951),
            BookData("b4", "A Brief History of Time", "SCIENCE", 1988),
            BookData("b5", "The Hitchhiker's Guide", "FICTION", 1979),
            BookData("b6", "Thinking, Fast and Slow", "NON_FICTION", 2011),
            BookData("b7", "Ender's Game", "FICTION", 1985),
        )

        // Simulates an "old-fashioned" backend that does not understand cursors but
        // does implement pagination via offset/limit. In real code this would be a
        // service or repository call that accepts offset and limit parameters.
        fun highlightedBooks(
            offset: Int,
            limit: Int
        ): List<BookData> = ALL_BOOKS.filter { it.id in listOf("b1", "b3", "b5") }.drop(offset).take(limit)
    }

    // ── Node resolver ─────────────────────────────────────────────────────────

    class BookNodeResolver : NodeResolvers.Book() {
        override suspend fun resolve(ctx: Context): Book {
            val item = ALL_BOOKS.first { it.id == ctx.id.internalID }
            return Book.Builder(ctx)
                .id(ctx.id)
                .title(item.title)
                .genre(Genre.valueOf(item.genre))
                .year(item.year)
                .build()
        }
    }

    // ── STRATEGY 1: fromList ──────────────────────────────────────────────────
    // Hand over the complete dataset. The framework reads first/after/last/before
    // from the context, extracts the right window, and assigns OffsetCursors.

    @Resolver
    class BooksResolver : QueryResolvers.Books() {
        override suspend fun resolve(ctx: Context): BookConnection =
            BookConnection.Builder(ctx)
                .fromList(ALL_BOOKS) { item ->
                    ctx.nodeFor(ctx.globalIDFor(Book.Reflection, item.id))
                }.build()
    }

    // ── STRATEGY 2: fromEdges ─────────────────────────────────────────────────
    // Build each BookEdge manually when the edge carries extra data beyond just
    // the node. Here the genre filter is the reason this edge is in the result,
    // so we surface that as a reason field on the edge itself.

    @Resolver
    class BooksByGenreResolver : QueryResolvers.BooksByGenre() {
        override suspend fun resolve(ctx: Context): BookConnection {
            val genre = ctx.arguments.genre
            val offsetLimit = ctx.arguments.toOffsetLimit()
            val filtered = ALL_BOOKS.filter { it.genre == genre.name }
            val fetched = filtered.drop(offsetLimit.offset).take(offsetLimit.limit + 1)
            val hasNextPage = fetched.size > offsetLimit.limit
            val page = fetched.take(offsetLimit.limit)
            val edges = page.mapIndexed { idx, item ->
                BookEdge.Builder(ctx)
                    .cursor(OffsetCursor.fromOffset(offsetLimit.offset + idx).value)
                    .reason(genre.name)
                    .node(ctx.nodeFor(ctx.globalIDFor(Book.Reflection, item.id)))
                    .build()
            }
            return BookConnection.Builder(ctx)
                .fromEdges(edges, hasNextPage = hasNextPage, hasPreviousPage = offsetLimit.offset > 0)
                .build()
        }
    }

    // ── STRATEGY 3: fromSlice (wrapping an offset/limit backend) ─────────────
    // When the data source already paginates via offset/limit (a common legacy
    // pattern), request one extra row to cheaply detect whether a next page
    // exists, then pass the trimmed slice to fromSlice.

    @Resolver
    class HighlightedBooksResolver : QueryResolvers.HighlightedBooks() {
        override suspend fun resolve(ctx: Context): BookConnection {
            val offsetLimit = ctx.arguments.toOffsetLimit()
            val fetched = highlightedBooks(offsetLimit.offset, offsetLimit.limit + 1)
            val hasNextPage = fetched.size > offsetLimit.limit
            return BookConnection.Builder(ctx)
                .fromSlice(fetched.take(offsetLimit.limit), hasNextPage) { item ->
                    ctx.nodeFor(ctx.globalIDFor(Book.Reflection, item.id))
                }
                .build()
        }
    }

    // ── Tests: fromList ───────────────────────────────────────────────────────

    @Test
    fun `fromList - first page returns requested count with hasNextPage true`() {
        execute("{ books(first: 3) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "books" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Dune" } },
                            { "node" to { "title" to "The Selfish Gene" } },
                            { "node" to { "title" to "Foundation" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to true
                            "hasPreviousPage" to false
                        }
                    }
                }
            }
    }

    @Test
    fun `fromList - backward pagination with last returns final items`() {
        execute("{ books(last: 2) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "books" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Thinking, Fast and Slow" } },
                            { "node" to { "title" to "Ender's Game" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to false
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    @Test
    fun `fromList - after cursor advances the window`() {
        val after = OffsetCursor.fromOffset(2).value
        execute("{ books(first: 2, after: \"$after\") { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "books" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "A Brief History of Time" } },
                            { "node" to { "title" to "The Hitchhiker's Guide" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to true
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    // ── Tests: fromEdges ──────────────────────────────────────────────────────

    @Test
    fun `fromEdges - reason field reflects the genre filter`() {
        execute("{ booksByGenre(genre: FICTION, first: 2) { edges { reason node { title } } pageInfo { hasNextPage } } }")
            .assertEquals {
                "data" to {
                    "booksByGenre" to {
                        "edges" to arrayOf(
                            {
                                "reason" to "FICTION"
                                "node" to { "title" to "Dune" }
                            },
                            {
                                "reason" to "FICTION"
                                "node" to { "title" to "Foundation" }
                            },
                        )
                        "pageInfo" to { "hasNextPage" to true }
                    }
                }
            }
    }

    @Test
    fun `fromEdges - hasNextPage false when all matching edges fit on one page`() {
        execute("{ booksByGenre(genre: SCIENCE, first: 10) { edges { reason node { title } } pageInfo { hasNextPage } } }")
            .assertEquals {
                "data" to {
                    "booksByGenre" to {
                        "edges" to arrayOf(
                            {
                                "reason" to "SCIENCE"
                                "node" to { "title" to "The Selfish Gene" }
                            },
                            {
                                "reason" to "SCIENCE"
                                "node" to { "title" to "A Brief History of Time" }
                            },
                        )
                        "pageInfo" to { "hasNextPage" to false }
                    }
                }
            }
    }

    // ── Tests: fromSlice (offset/limit backend) ───────────────────────────────

    @Test
    fun `fromSlice offset-limit - returns all highlighted books when page is large`() {
        execute("{ highlightedBooks(first: 10) { edges { node { title } } pageInfo { hasNextPage } } }")
            .assertEquals {
                "data" to {
                    "highlightedBooks" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Dune" } },
                            { "node" to { "title" to "Foundation" } },
                            { "node" to { "title" to "The Hitchhiker's Guide" } },
                        )
                        "pageInfo" to { "hasNextPage" to false }
                    }
                }
            }
    }

    @Test
    fun `fromSlice offset-limit - hasNextPage true when more items remain`() {
        execute("{ highlightedBooks(first: 2) { edges { node { title } } pageInfo { hasNextPage } } }")
            .assertEquals {
                "data" to {
                    "highlightedBooks" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Dune" } },
                            { "node" to { "title" to "Foundation" } },
                        )
                        "pageInfo" to { "hasNextPage" to true }
                    }
                }
            }
    }
}
