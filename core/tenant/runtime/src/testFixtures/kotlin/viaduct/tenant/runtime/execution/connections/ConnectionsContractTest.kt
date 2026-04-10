package viaduct.tenant.runtime.execution.connections

import org.junit.jupiter.api.Test
import viaduct.api.connection.OffsetCursor
import viaduct.api.testing.TestSchema
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [ConnectionBuilder] — three pagination strategies on
 * a single Post/PostEdge/PostConnection schema:
 *
 *  posts       — [fromList]  full dataset, framework slices and assigns cursors
 *  pagedPosts  — [fromSlice] caller fetches limit+1, passes hasNextPage explicitly
 *  rankedPosts — [fromEdges] caller builds edges manually; PostEdge carries a [score] field
 */
@TestSchema(
    """
    type Post {
      id: String!
      title: String!
    }

    type PostEdge @edge {
      node: Post
      cursor: String!
      score: Float
    }

    type PostConnection @connection {
      edges: [PostEdge!]!
      pageInfo: PageInfo!
    }

    extend type Query {
      posts(first: Int, after: String, last: Int, before: String): PostConnection! @resolver
      pagedPosts(first: Int, after: String): PostConnection! @resolver
      rankedPosts(last: Int, before: String): PostConnection! @resolver
    }
    """
)
abstract class ConnectionsContractTest : FeatureAppTestBase() {
    companion object {
        // (id, title, score) — scores decrease with post number; used by rankedPosts (fromEdges)
        val ALL_POSTS = (1..10).map { i -> Triple("post-$i", "Post $i", (11 - i).toDouble()) }
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
