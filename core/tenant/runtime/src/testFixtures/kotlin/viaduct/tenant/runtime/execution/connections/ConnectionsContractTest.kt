@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.tenant.runtime.execution.connections

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.api.types.OffsetCursor
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

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

    enum ConnectionStatus {
      ACTIVE
    }

    interface ConnectionMetadata {
      label: String!
    }

    type RankedMetadata implements ConnectionMetadata {
      label: String!
    }

    type ConnectionOwner implements Node {
      id: ID!
    }

    type PostEdge @edge {
      node: Post
      cursor: String!
      score: Float
    }

    type PostConnection @connection {
      edges: [PostEdge!]!
      pageInfo: PageInfo!
      totalCount: Int
      status: ConnectionStatus
      publishedAt: DateTime
      publishedHistory: [DateTime!]
      labels: [String!]
      metadata: ConnectionMetadata
      metadataHistory: [ConnectionMetadata!]
      ownerID: ID @idOf(type: "ConnectionOwner")
    }

    extend type Query {
      posts(first: Int, after: String, last: Int, before: String): PostConnection! @resolver
      pagedPosts(first: Int, after: String, last: Int, before: String): PostConnection! @resolver
      rankedPosts(last: Int, before: String): PostConnection! @resolver
      firstOnlyPosts(first: Int): PostConnection! @resolver
      lastOnlyPosts(last: Int): PostConnection! @resolver
      sparsePosts(first: Int, after: String): PostConnection! @resolver
      unpagedPosts: PostConnection! @resolver
      filteredPosts(category: String): PostConnection! @resolver
    }
    """
)
abstract class ConnectionsContractTest : KotlinFeatureAppTestContractBase() {
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

    @Test
    fun `fromSlice - backward pagination without before reuses total-count-resolved bounds`() {
        execute(
            "{ pagedPosts(last: 3) { edges { node { title } cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"
        ).assertEquals {
            "data" to {
                "pagedPosts" to {
                    "edges" to arrayOf(
                        {
                            "node" to { "title" to "Post 8" }
                            "cursor" to OffsetCursor.fromOffset(7).value
                        },
                        {
                            "node" to { "title" to "Post 9" }
                            "cursor" to OffsetCursor.fromOffset(8).value
                        },
                        {
                            "node" to { "title" to "Post 10" }
                            "cursor" to OffsetCursor.fromOffset(9).value
                        },
                    )
                    "pageInfo" to {
                        "hasNextPage" to false
                        "hasPreviousPage" to true
                        "startCursor" to OffsetCursor.fromOffset(7).value
                        "endCursor" to OffsetCursor.fromOffset(9).value
                    }
                }
            }
        }
    }

    @Test
    fun `fromSlice - backward pagination with before keeps cursor-anchored bounds`() {
        val before = OffsetCursor.fromOffset(7).value
        execute(
            "{ pagedPosts(last: 3, before: \"$before\") { edges { node { title } cursor } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"
        ).assertEquals {
            "data" to {
                "pagedPosts" to {
                    "edges" to arrayOf(
                        {
                            "node" to { "title" to "Post 5" }
                            "cursor" to OffsetCursor.fromOffset(4).value
                        },
                        {
                            "node" to { "title" to "Post 6" }
                            "cursor" to OffsetCursor.fromOffset(5).value
                        },
                        {
                            "node" to { "title" to "Post 7" }
                            "cursor" to OffsetCursor.fromOffset(6).value
                        },
                    )
                    "pageInfo" to {
                        "hasNextPage" to true
                        "hasPreviousPage" to true
                        "startCursor" to OffsetCursor.fromOffset(4).value
                        "endCursor" to OffsetCursor.fromOffset(6).value
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

    // =========================================================================
    // Partial pagination shapes: the generated arguments class implements the
    // whole ConnectionArguments pair even though the schema declares only one
    // member (first-only is FORWARD, last-only is BACKWARD). These execute the
    // generated arguments type, so they fail if the missing counterpart getter
    // (getAfter / getBefore) is not synthesized.
    // =========================================================================

    // =========================================================================
    // Additional connection fields beyond edges/pageInfo (e.g. totalCount).
    // The generated connection builder must let a resolver populate these
    // alongside the pagination-produced edges/pageInfo before build().
    // =========================================================================

    @Test
    fun `connection field beyond edges and pageInfo is populated via the builder`() {
        execute("{ posts(first: 2) { totalCount edges { node { title } } } }")
            .assertEquals {
                "data" to {
                    "posts" to {
                        "totalCount" to 10
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 1" } },
                            { "node" to { "title" to "Post 2" } },
                        )
                    }
                }
            }
    }

    // =========================================================================
    // Null buildNode results are omitted (no edge created). sparsePosts maps
    // even-numbered posts (Post 2, 4, …) to null, so only odd posts survive.
    // Surviving edges keep their original-position cursors.
    // =========================================================================

    @Test
    fun `null buildNode results are omitted from the connection`() {
        // first: 5 spans source offsets 0..4 (Post 1..5); the even ones (2, 4) map to null.
        execute("{ sparsePosts(first: 5) { edges { node { title } cursor } } }")
            .assertEquals {
                "data" to {
                    "sparsePosts" to {
                        "edges" to arrayOf(
                            {
                                "node" to { "title" to "Post 1" }
                                "cursor" to OffsetCursor.fromOffset(0).value
                            },
                            {
                                "node" to { "title" to "Post 3" }
                                "cursor" to OffsetCursor.fromOffset(2).value
                            },
                            {
                                "node" to { "title" to "Post 5" }
                                "cursor" to OffsetCursor.fromOffset(4).value
                            },
                        )
                    }
                }
            }
    }

    @Test
    fun `first-only field paginates forward from the head`() {
        execute("{ firstOnlyPosts(first: 2) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "firstOnlyPosts" to {
                        "edges" to arrayOf(
                            { "node" to { "title" to "Post 1" } },
                            { "node" to { "title" to "Post 2" } },
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
    fun `last-only field paginates backward from the tail`() {
        execute("{ lastOnlyPosts(last: 2) { edges { node { title } } pageInfo { hasNextPage hasPreviousPage } } }")
            .assertEquals {
                "data" to {
                    "lastOnlyPosts" to {
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

    @Test
    fun `unpaged connection uses ordinary context and preserves field conversions`() {
        execute(
            """
                {
                  unpagedPosts {
                    status
                    publishedAt
                    publishedHistory
                    labels
                    metadata { label }
                    metadataHistory { label }
                    ownerID
                    edges { node { title } }
                    pageInfo { hasNextPage hasPreviousPage }
                  }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "unpagedPosts" to {
                    "status" to "ACTIVE"
                    "publishedAt" to "2026-07-30T12:34:56.000Z"
                    "publishedHistory" to listOf("2026-07-29T12:34:56.000Z")
                    "labels" to listOf("featured", "unpaged")
                    "metadata" to { "label" to "primary" }
                    "metadataHistory" to arrayOf({ "label" to "historical" })
                    "ownerID" to GlobalIDCodecDefault.serialize("ConnectionOwner", "owner-1")
                    "edges" to emptyList<Any>()
                    "pageInfo" to {
                        "hasNextPage" to false
                        "hasPreviousPage" to false
                    }
                }
            }
        }
    }

    @Test
    fun `filter-only connection uses ordinary context`() {
        execute("{ filteredPosts(category: \"featured\") { totalCount edges { cursor } } }")
            .assertEquals {
                "data" to {
                    "filteredPosts" to {
                        "totalCount" to 0
                        "edges" to emptyList<Any>()
                    }
                }
            }
    }
}
