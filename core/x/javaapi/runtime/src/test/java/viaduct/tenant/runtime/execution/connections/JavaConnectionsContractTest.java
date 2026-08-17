package viaduct.tenant.runtime.execution.connections;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.OffsetCursor;
import viaduct.java.api.types.OffsetLimit;
import viaduct.tenant.runtime.execution.connections.resolverbases.QueryResolvers;

/**
 * Java implementation of the shared {@link ConnectionsContractTest}, exercising all three {@link
 * viaduct.java.api.internal.ConnectionBuilder} strategies against the shared
 * Post/PostEdge/PostConnection schema. Mirrors {@code KotlinConnectionsContractTest}.
 */
public class JavaConnectionsContractTest extends ConnectionsContractTest {

  /** (id, title, score) — scores decrease with post number; used by rankedPosts (fromEdges). */
  private record PostData(String id, String title, double score) {}

  private static final List<PostData> ALL_POSTS = new ArrayList<>();

  static {
    for (int i = 1; i <= 10; i++) {
      ALL_POSTS.add(new PostData("post-" + i, "Post " + i, 11 - i));
    }
  }

  // ── fromList: hand the full dataset to the framework ──────────────────────

  @Resolver
  public static class PostsResolver extends QueryResolvers.Posts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.Posts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .totalCount(ALL_POSTS.size())
              .fromList(ALL_POSTS, p -> Post.builder(ctx).id(p.id()).title(p.title()).build())
              .build());
    }
  }

  // ── fromSlice: DB limit+1 pattern ─────────────────────────────────────────

  @Resolver
  public static class PagedPostsResolver extends QueryResolvers.PagedPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.PagedPosts.Context ctx) {
      OffsetLimit offsetLimit =
          ctx.getArguments().requiresTotalCountForOffsetLimit()
              ? ctx.getArguments()
                  .toOffsetLimit(ALL_POSTS.size(), ConnectionArguments.DEFAULT_PAGE_SIZE)
              : ctx.getArguments().toOffsetLimit();
      int limit = offsetLimit.limit();
      List<PostData> fetched = slice(ALL_POSTS, offsetLimit.offset(), limit + 1);
      boolean hasNextPage = fetched.size() > limit;
      List<PostData> page = hasNextPage ? fetched.subList(0, limit) : fetched;
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .fromSlice(
                  page,
                  offsetLimit,
                  hasNextPage,
                  p -> Post.builder(ctx).id(p.id()).title(p.title()).build())
              .build());
    }
  }

  // ── fromEdges: manually constructed edges with a custom score field ────────
  // Uses last/before (backward) pagination. The schema only exposes last/before, so we read those
  // args directly, treating a missing before cursor as "end of list".

  @Resolver
  public static class RankedPostsResolver extends QueryResolvers.RankedPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.RankedPosts.Context ctx) {
      int last = ctx.getArguments().getLast() != null ? ctx.getArguments().getLast() : 20;
      String before = ctx.getArguments().getBefore();
      int beforeOffset = before != null ? new OffsetCursor(before).toOffset() : ALL_POSTS.size();
      int startOffset = Math.max(0, beforeOffset - last);
      List<PostData> page = slice(ALL_POSTS, startOffset, Math.min(last, beforeOffset));

      List<PostEdge> edges = new ArrayList<>();
      for (int idx = 0; idx < page.size(); idx++) {
        PostData p = page.get(idx);
        edges.add(
            PostEdge.builder(ctx)
                .cursor(OffsetCursor.fromOffset(startOffset + idx).getValue())
                .score(p.score())
                .node(Post.builder(ctx).id(p.id()).title(p.title()).build())
                .build());
      }
      boolean hasNextPage = startOffset + page.size() < ALL_POSTS.size();
      boolean hasPreviousPage = startOffset > 0;
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx).fromEdges(edges, hasNextPage, hasPreviousPage).build());
    }
  }

  // ── Partial pagination shapes: first-only (FORWARD) and last-only (BACKWARD). ──
  // fromList reads the offset/limit off the generated arguments type, exercising the synthesized
  // getAfter()/getBefore() counterparts.

  @Resolver
  public static class FirstOnlyPostsResolver extends QueryResolvers.FirstOnlyPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.FirstOnlyPosts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .fromList(ALL_POSTS, p -> Post.builder(ctx).id(p.id()).title(p.title()).build())
              .build());
    }
  }

  @Resolver
  public static class LastOnlyPostsResolver extends QueryResolvers.LastOnlyPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.LastOnlyPosts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .fromList(ALL_POSTS, p -> Post.builder(ctx).id(p.id()).title(p.title()).build())
              .build());
    }
  }

  // ── null buildNode omission: even-numbered posts map to null and are dropped. ──

  @Resolver
  public static class SparsePostsResolver extends QueryResolvers.SparsePosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.SparsePosts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .fromList(
                  ALL_POSTS,
                  p -> {
                    // "post-N" → keep only odd N; even ones map to null (omitted).
                    int n = Integer.parseInt(p.id().substring("post-".length()));
                    return n % 2 == 0
                        ? null
                        : Post.builder(ctx).id(p.id()).title(p.title()).build();
                  })
              .build());
    }
  }

  @Resolver
  public static class UnpagedPostsResolver extends QueryResolvers.UnpagedPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.UnpagedPosts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx)
              .status(ConnectionStatus.ACTIVE)
              .publishedAt(Instant.parse("2026-07-30T12:34:56Z"))
              .publishedHistory(List.of(Instant.parse("2026-07-29T12:34:56Z")))
              .labels(List.of("featured", "unpaged"))
              .metadata(RankedMetadata.builder(ctx).label("primary").build())
              .metadataHistory(List.of(RankedMetadata.builder(ctx).label("historical").build()))
              .ownerID(ctx.globalIDFor(Type.ofClass(ConnectionOwner.class), "owner-1"))
              .fromEdges(List.of())
              .build());
    }
  }

  @Resolver
  public static class FilteredPostsResolver extends QueryResolvers.FilteredPosts {
    @Override
    public CompletableFuture<PostConnection> resolve(QueryResolvers.FilteredPosts.Context ctx) {
      return CompletableFuture.completedFuture(
          PostConnection.builder(ctx).totalCount(0).fromEdges(List.of()).build());
    }
  }

  /** {@code items.drop(offset).take(count)}, clamped to the list bounds. */
  private static List<PostData> slice(List<PostData> items, int offset, int count) {
    if (offset >= items.size() || count <= 0) {
      return List.of();
    }
    int from = Math.max(0, offset);
    int to = (int) Math.min((long) items.size(), (long) from + count);
    return items.subList(from, to);
  }
}
