package viaduct.java.api.internal.connbuildertest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.types.OffsetCursor;

/**
 * Unit tests for {@code ConnectionBuilder}, focused on representation-independent cursor
 * extraction: {@code fromEdges} must read each edge's {@code cursor} whether the edge is
 * builder-backed (map) or engine-backed ({@link EngineObjectData.Sync}). An engine-backed edge has
 * no builder map, so reading the cursor via the map representation would NPE.
 *
 * <p>Lives in a dedicated package so the {@code PageInfo} test GRT the builder loads by {@code
 * <connectionPackage>.PageInfo} resolves to {@link PageInfo} here.
 */
class ConnectionBuilderTest {

  @Test
  void fromEdges_extractsCursorsFromEngineBackedEdges() {
    FakeConnectionContext ctx = new FakeConnectionContext();
    // Engine-backed edges: no builder map. Reading the cursor via getJavaMapData() would NPE.
    TestEdge first =
        new TestEdge(ctx, new FakeSync(Map.of("cursor", OffsetCursor.fromOffset(0).getValue())));
    TestEdge last =
        new TestEdge(ctx, new FakeSync(Map.of("cursor", OffsetCursor.fromOffset(2).getValue())));

    TestConnection connection =
        new TestConnection.Builder(ctx).fromEdges(List.of(first, last), false, false).build();

    PageInfo pageInfo = connection.pageInfo();
    assertEquals(OffsetCursor.fromOffset(0).getValue(), pageInfo.startCursor());
    assertEquals(OffsetCursor.fromOffset(2).getValue(), pageInfo.endCursor());
    assertEquals(2, connection.edges().size());
  }

  @Test
  void fromEdges_extractsCursorsFromBuilderBackedEdges() {
    FakeConnectionContext ctx = new FakeConnectionContext();
    // Builder-backed (map) edges — the pre-existing representation.
    TestEdge first = new TestEdge(ctx, Map.of("cursor", OffsetCursor.fromOffset(5).getValue()));
    TestEdge last = new TestEdge(ctx, Map.of("cursor", OffsetCursor.fromOffset(9).getValue()));

    TestConnection connection =
        new TestConnection.Builder(ctx).fromEdges(List.of(first, last), false, false).build();

    PageInfo pageInfo = connection.pageInfo();
    assertEquals(OffsetCursor.fromOffset(5).getValue(), pageInfo.startCursor());
    assertEquals(OffsetCursor.fromOffset(9).getValue(), pageInfo.endCursor());
  }

  @Test
  void fromEdges_buildsWithOrdinaryExecutionContext() {
    FakeExecutionContext ctx = new FakeExecutionContext();
    TestEdge edge = new TestEdge(ctx, Map.of("cursor", OffsetCursor.fromOffset(3).getValue()));

    TestConnection connection = new TestConnection.Builder(ctx).fromEdges(List.of(edge)).build();

    assertEquals(1, connection.edges().size());
    assertEquals(OffsetCursor.fromOffset(3).getValue(), connection.pageInfo().startCursor());
  }

  @Test
  void fromList_requiresConnectionArguments() {
    FakeExecutionContext ctx = new FakeExecutionContext();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> new TestConnection.Builder(ctx).fromList(List.<TestNode>of(), node -> node));

    assertTrue(exception.getMessage().contains("requires a ConnectionFieldExecutionContext"));
  }
}
