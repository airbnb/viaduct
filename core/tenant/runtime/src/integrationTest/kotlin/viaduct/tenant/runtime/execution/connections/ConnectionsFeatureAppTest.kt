@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.runtime.execution.connections

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.connection.OffsetCursor
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.connections.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Feature app tests for [ConnectionBuilder] — three pagination strategies on
 * a single Post/PostEdge/PostConnection schema:
 *
 *  posts       — [fromList]  full dataset, framework slices and assigns cursors
 *  pagedPosts  — [fromSlice] caller fetches limit+1, passes hasNextPage explicitly
 *  rankedPosts — [fromEdges] caller builds edges manually; PostEdge carries a [score] field
 */
class ConnectionsFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        |
        | type Post {
        |   id: String!
        |   title: String!
        | }
        |
        | type PostEdge @edge {
        |   node: Post
        |   cursor: String!
        |   score: Float
        | }
        |
        | type PostConnection @connection {
        |   edges: [PostEdge!]!
        |   pageInfo: PageInfo!
        | }
        |
        | extend type Query {
        |   posts(first: Int, after: String, last: Int, before: String): PostConnection! @resolver
        |   pagedPosts(first: Int, after: String): PostConnection! @resolver
        |   rankedPosts(last: Int, before: String): PostConnection! @resolver
        | }
        |
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        // (id, title, score) — scores decrease with post number; used by rankedPosts (fromEdges)
        val ALL_POSTS = (1..10).map { i -> Triple("post-$i", "Post $i", (11 - i).toDouble()) }
    }

    // ── fromList: hand the full dataset to the framework ──────────────────────

    @Resolver
    class PostsResolver : QueryResolvers.Posts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .fromList(ALL_POSTS) { (id, title, _) ->
                    Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
    }

    // ── fromSlice: DB limit+1 pattern ─────────────────────────────────────────

    @Resolver
    class PagedPostsResolver : QueryResolvers.PagedPosts() {
        override suspend fun resolve(ctx: Context): PostConnection {
            val (offset, limit) = ctx.arguments.toOffsetLimit().let { it.offset to it.limit }
            val fetched = ALL_POSTS.drop(offset).take(limit + 1)
            val hasNextPage = fetched.size > limit
            return PostConnection.Builder(ctx)
                .fromSlice(if (hasNextPage) fetched.dropLast(1) else fetched, hasNextPage) { (id, title, _) ->
                    Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
        }
    }

    // ── fromEdges: manually constructed edges with a custom score field ────────
    // Uses last/before (backward) pagination. Because the schema only exposes
    // last/before, we read those args directly: treat a missing before cursor as
    // "end of list" so the math is identical in both cases.

    @Resolver
    class RankedPostsResolver : QueryResolvers.RankedPosts() {
        override suspend fun resolve(ctx: Context): PostConnection {
            val last = ctx.arguments.last ?: 20
            val beforeOffset = ctx.arguments.before?.let { OffsetCursor(it).toOffset() } ?: ALL_POSTS.size
            val startOffset = maxOf(0, beforeOffset - last)
            val page = ALL_POSTS.drop(startOffset).take(minOf(last, beforeOffset))
            val edges = page.mapIndexed { idx, (id, title, score) ->
                PostEdge.Builder(ctx)
                    .cursor(OffsetCursor.fromOffset(startOffset + idx).value)
                    .score(score)
                    .node(Post.Builder(ctx).id(id).title(title).build())
                    .build()
            }
            return PostConnection.Builder(ctx)
                .fromEdges(edges, hasNextPage = startOffset + page.size < ALL_POSTS.size, hasPreviousPage = startOffset > 0)
                .build()
        }
    }

    // =========================================================================
    // fromList tests
    // =========================================================================

    @Test
    fun `fromList - first page returns requested count with hasNextPage true`() {
        execute("{ posts(first: 3) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 1" } },
                            { "node" to { "title" to "Post 2" } },
                            { "node" to { "title" to "Post 3" } },
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
    fun `fromList - after cursor advances the window and sets hasPreviousPage true`() {
        val after = OffsetCursor.fromOffset(2).value
        execute("{ posts(first: 3, after: \"$after\") { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 4" } },
                            { "node" to { "title" to "Post 5" } },
                            { "node" to { "title" to "Post 6" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to true
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    @Test
    fun `fromList - last page has hasNextPage false`() {
        val after = OffsetCursor.fromOffset(7).value
        execute("{ posts(first: 5, after: \"$after\") { edges { node { title } } pageInfo { hasNextPage } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 9" } },
                            { "node" to { "title" to "Post 10" } },
                        )
                        "pageInfo" to { "hasNextPage" to false }
                    }
                }
            }
    }

    @Test
    fun `fromList - backward pagination with last returns final items`() {
        execute("{ posts(last: 3) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 8" } },
                            { "node" to { "title" to "Post 9" } },
                            { "node" to { "title" to "Post 10" } },
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
    fun `fromList - before cursor with last returns items before that position`() {
        val before = OffsetCursor.fromOffset(7).value
        execute("{ posts(last: 3, before: \"$before\") { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 5" } },
                            { "node" to { "title" to "Post 6" } },
                            { "node" to { "title" to "Post 7" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to true
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    @Test
    fun `fromList - edge cursors and pageInfo cursors are consistent`() {
        execute("{ posts(first: 3) { edges { cursor } pageInfo { startCursor endCursor } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "edges" to arrayOf(
                            { "cursor" to OffsetCursor.fromOffset(0).value },
                            { "cursor" to OffsetCursor.fromOffset(1).value },
                            { "cursor" to OffsetCursor.fromOffset(2).value },
                        )
                        "pageInfo" to {
                            "startCursor" to OffsetCursor.fromOffset(0).value
                            "endCursor" to OffsetCursor.fromOffset(2).value
                        }
                    }
                }
            }
    }

    // =========================================================================
    // fromSlice tests
    // =========================================================================

    @Test
    fun `fromSlice - hasNextPage detected from limit+1 fetch`() {
        execute("{ pagedPosts(first: 3) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "pagedPosts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 1" } },
                            { "node" to { "title" to "Post 2" } },
                            { "node" to { "title" to "Post 3" } },
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
    fun `fromSlice - last page detected when fetched slice is smaller than limit`() {
        val after = OffsetCursor.fromOffset(7).value
        execute("{ pagedPosts(first: 5, after: \"$after\") { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "pagedPosts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 9" } },
                            { "node" to { "title" to "Post 10" } },
                        )
                        "pageInfo" to {
                            "hasNextPage" to false
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    // =========================================================================
    // fromEdges tests
    // =========================================================================

    @Test
    fun `fromEdges - custom score field is present on each edge`() {
        execute("{ rankedPosts(last: 3) { edges { score node { title } } } }")
            .assertEquals {
                "data" to {
                    "rankedPosts" to {
                        "edges" to arrayOf(
                            {
                                "score" to 3.0
                                "node" to { "title" to "Post 8" }
                            },
                            {
                                "score" to 2.0
                                "node" to { "title" to "Post 9" }
                            },
                            {
                                "score" to 1.0
                                "node" to { "title" to "Post 10" }
                            },
                        )
                    }
                }
            }
    }

    @Test
    fun `fromEdges - explicit hasNextPage and hasPreviousPage are respected`() {
        val before = OffsetCursor.fromOffset(7).value
        execute("{ rankedPosts(last: 3, before: \"$before\") { pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "rankedPosts" to {
                        "pageInfo" to {
                            "hasNextPage" to true
                            "hasPreviousPage" to true
                        }
                    }
                }
            }
    }

    @Test
    fun `fromEdges - pageInfo cursors are sourced from first and last edge cursors`() {
        execute("{ rankedPosts(last: 3) { pageInfo { startCursor endCursor } } }")
            .assertEquals {
                "data" to {
                    "rankedPosts" to {
                        "pageInfo" to {
                            "startCursor" to OffsetCursor.fromOffset(7).value
                            "endCursor" to OffsetCursor.fromOffset(9).value
                        }
                    }
                }
            }
    }
}
