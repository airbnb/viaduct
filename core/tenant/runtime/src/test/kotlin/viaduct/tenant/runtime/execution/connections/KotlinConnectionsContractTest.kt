@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.connections

import viaduct.api.Resolver
import viaduct.api.connection.OffsetCursor
import viaduct.tenant.runtime.execution.connections.resolverbases.QueryResolvers

class KotlinConnectionsContractTest : ConnectionsContractTest() {
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
}
