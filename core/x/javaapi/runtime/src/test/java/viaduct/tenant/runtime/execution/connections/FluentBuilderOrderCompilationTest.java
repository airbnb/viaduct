package viaduct.tenant.runtime.execution.connections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.OffsetLimit;
import viaduct.tenant.runtime.execution.connections.resolverbases.QueryResolvers;

class FluentBuilderOrderCompilationTest {
  private static final class FilterArguments implements Arguments {}

  @Test
  void generatedConnectionSetterRemainsAvailableAfterEveryPaginationOverload() {
    Function<QueryResolvers.Posts.Context, PostConnection> fromEdges =
        context ->
            PostConnection.builder(context).fromEdges(List.<PostEdge>of()).totalCount(0).build();
    Function<QueryResolvers.Posts.Context, PostConnection> fromEdgesWithPageInfo =
        context ->
            PostConnection.builder(context)
                .fromEdges(List.<PostEdge>of(), false, false)
                .totalCount(0)
                .build();
    Function<QueryResolvers.Posts.Context, PostConnection> fromSlice =
        context ->
            PostConnection.builder(context)
                .fromSlice(List.<Post>of(), false, post -> post)
                .totalCount(0)
                .build();
    Function<QueryResolvers.Posts.Context, PostConnection> fromResolvedSlice =
        context ->
            PostConnection.builder(context)
                .fromSlice(List.<Post>of(), new OffsetLimit(0, 10), false, post -> post)
                .totalCount(0)
                .build();
    Function<QueryResolvers.Posts.Context, PostConnection> fromList =
        context ->
            PostConnection.builder(context)
                .fromList(List.<Post>of(), post -> post)
                .totalCount(0)
                .build();

    assertThat(fromEdges).isNotNull();
    assertThat(fromEdgesWithPageInfo).isNotNull();
    assertThat(fromSlice).isNotNull();
    assertThat(fromResolvedSlice).isNotNull();
    assertThat(fromList).isNotNull();
  }

  @Test
  void generatedConnectionBuilderAcceptsOrdinaryResolverContexts() {
    Function<FieldExecutionContext<Query, Query, Arguments.None, PostConnection>, PostConnection>
        unpaged = context -> PostConnection.builder(context).fromEdges(List.<PostEdge>of()).build();
    Function<FieldExecutionContext<Query, Query, FilterArguments, PostConnection>, PostConnection>
        filtered =
            context -> PostConnection.builder(context).fromEdges(List.<PostEdge>of()).build();

    assertThat(unpaged).isNotNull();
    assertThat(filtered).isNotNull();
  }
}
