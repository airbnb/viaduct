package viaduct.java.api.internal.connbuildertest;

import graphql.schema.GraphQLObjectType;
import java.util.Map;
import viaduct.engine.api.EngineObjectData;

/** In-memory EngineObjectData.Sync backed by a map (mirrors ObjectBaseTest.FakeSync). */
final class FakeSync implements EngineObjectData.Sync {
  private final Map<String, Object> values;

  FakeSync(Map<String, Object> values) {
    this.values = values;
  }

  @Override
  public Object get(String selection) {
    return values.get(selection);
  }

  @Override
  public Object getOrNull(String selection) {
    return values.get(selection);
  }

  @Override
  public boolean isPresent(String selection) {
    return values.containsKey(selection);
  }

  @Override
  public Iterable<String> getSelections() {
    return values.keySet();
  }

  @Override
  public Object fetch(
      String selection, kotlin.coroutines.Continuation<? super Object> continuation) {
    return values.get(selection);
  }

  @Override
  public Object fetchOrNull(
      String selection, kotlin.coroutines.Continuation<? super Object> continuation) {
    return values.get(selection);
  }

  @Override
  public Object fetchSelections(
      kotlin.coroutines.Continuation<? super Iterable<String>> continuation) {
    return values.keySet();
  }

  @Override
  public GraphQLObjectType getType() {
    return GraphQLObjectType.newObject().name("TestEdge").build();
  }
}
