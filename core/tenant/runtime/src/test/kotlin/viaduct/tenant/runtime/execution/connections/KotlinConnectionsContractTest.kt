@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.connections

import java.time.Instant
import viaduct.api.resolver.Resolver
import viaduct.api.types.OffsetCursor
import viaduct.tenant.runtime.execution.connections.resolverbases.QueryResolvers

class KotlinConnectionsContractTest : ConnectionsContractTest() {
    // ── fromList: hand the full dataset to the framework ──────────────────────

    @Resolver
    class PostsResolver : QueryResolvers.Posts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .totalCount(ALL_POSTS.size)
                .fromList(ALL_POSTS) { (id, title, _) ->
                    Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
    }

    // ── fromSlice: DB limit+1 pattern ─────────────────────────────────────────

    @Resolver
    class PagedPostsResolver : QueryResolvers.PagedPosts() {
        override suspend fun resolve(ctx: Context): PostConnection {
            val offsetLimit =
                if (ctx.arguments.requiresTotalCountForOffsetLimit()) {
                    // Named `totalCount` picks the count-aware overload; a bare positional Int
                    // would bind to toOffsetLimit(defaultPageSize) instead (no-count) and yield a
                    // negative tail-relative offset.
                    ctx.arguments.toOffsetLimit(totalCount = ALL_POSTS.size)
                } else {
                    ctx.arguments.toOffsetLimit()
                }
            val fetched = ALL_POSTS.drop(offsetLimit.offset).take(offsetLimit.limit + 1)
            val hasNextPage = fetched.size > offsetLimit.limit
            return PostConnection.Builder(ctx)
                .fromSlice(if (hasNextPage) fetched.dropLast(1) else fetched, offsetLimit, hasNextPage) { (id, title, _) ->
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

    // ── Partial pagination shapes: first-only (FORWARD) and last-only (BACKWARD). ──
    // fromList reads the offset/limit off the generated arguments type, exercising the
    // synthesized getAfter()/getBefore() counterparts.

    @Resolver
    class FirstOnlyPostsResolver : QueryResolvers.FirstOnlyPosts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .fromList(ALL_POSTS) { (id, title, _) ->
                    Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
    }

    @Resolver
    class LastOnlyPostsResolver : QueryResolvers.LastOnlyPosts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .fromList(ALL_POSTS) { (id, title, _) ->
                    Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
    }

    // ── null buildNode omission: even-numbered posts map to null and are dropped. ──

    @Resolver
    class SparsePostsResolver : QueryResolvers.SparsePosts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .fromList(ALL_POSTS) { (id, title, _) ->
                    // "post-N" → keep only odd N; even ones map to null (omitted).
                    val n = id.removePrefix("post-").toInt()
                    if (n % 2 == 0) null else Post.Builder(ctx).id(id).title(title).build()
                }
                .build()
    }

    @Resolver
    class UnpagedPostsResolver : QueryResolvers.UnpagedPosts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.of(ctx) {
                status(ConnectionStatus.ACTIVE)
                publishedAt(Instant.parse("2026-07-30T12:34:56Z"))
                publishedHistory(listOf(Instant.parse("2026-07-29T12:34:56Z")))
                labels(listOf("featured", "unpaged"))
                metadata(RankedMetadata.Builder(ctx).label("primary").build())
                metadataHistory(listOf(RankedMetadata.Builder(ctx).label("historical").build()))
                ownerID(ctx.globalIDFor(ConnectionOwner.Reflection, "owner-1"))
                fromEdges(emptyList())
            }
    }

    @Resolver
    class FilteredPostsResolver : QueryResolvers.FilteredPosts() {
        override suspend fun resolve(ctx: Context): PostConnection =
            PostConnection.Builder(ctx)
                .totalCount(0)
                .fromEdges(emptyList())
                .build()
    }
}
