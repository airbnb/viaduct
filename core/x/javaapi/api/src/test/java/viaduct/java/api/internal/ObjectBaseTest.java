package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graphql.schema.GraphQLObjectType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.errors.FrameworkException;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * Unit tests for {@link ObjectBase}.
 *
 * <p>{@link ObjectBase} is an abstract base class for object-type GRTs, so it is exercised through
 * a minimal concrete subclass that exposes the {@code protected} fetch* methods. Tests assert on
 * the values returned through those accessors (state-based), covering the four construction paths
 * (engine, builder, node reference, root field reference), the field cache, scalar coercion, and
 * the list/object/enum/GlobalID fetch variants.
 */
class ObjectBaseTest {

  // ===== Test doubles =====

  /** Minimal concrete GRT exposing ObjectBase's protected fetch* methods for testing. */
  static final class TestObject extends ObjectBase {
    TestObject(@Nullable InternalContext context, EngineObjectData.Sync data) {
      super(context, data);
    }

    TestObject(@Nullable InternalContext context, Map<String, Object> data) {
      super(context, data);
    }

    TestObject(@Nullable InternalContext context, NodeReference ref) {
      super(context, ref);
    }

    TestObject(@Nullable InternalContext context, RootFieldReference ref) {
      super(context, ref);
    }

    <T> @Nullable T scalar(String field) {
      return fetchScalar(field);
    }

    <T> @Nullable T scalar(String field, String scalarType) {
      return fetchScalar(field, scalarType);
    }

    <T> @Nullable List<T> scalarList(String field) {
      return fetchScalarList(field);
    }

    <T> @Nullable List<T> scalarList(String field, String scalarType) {
      return fetchScalarList(field, scalarType);
    }

    <T extends ObjectBase> @Nullable T object(
        String field, BiFunction<InternalContext, EngineObjectData.Sync, T> ctor) {
      return fetchObject(field, ctor);
    }

    <T extends ObjectBase> @Nullable List<T> objectList(
        String field, BiFunction<InternalContext, EngineObjectData.Sync, T> ctor) {
      return fetchObjectList(field, ctor);
    }

    <E extends Enum<E>> @Nullable E enumValue(String field, Class<E> type) {
      return fetchEnum(field, type);
    }

    <E extends Enum<E>> @Nullable List<E> enumList(String field, Class<E> type) {
      return fetchEnumList(field, type);
    }

    <T extends NodeCompositeOutput> @Nullable GlobalID<T> globalId(String field) {
      return fetchGlobalID(field);
    }

    <T extends NodeCompositeOutput> @Nullable List<GlobalID<T>> globalIdList(String field) {
      return fetchGlobalIDList(field);
    }
  }

  enum Color {
    RED,
    GREEN
  }

  /** In-memory fake of EngineObjectData.Sync backed by a map. */
  static final class FakeSync implements EngineObjectData.Sync {
    private final Map<String, Object> values;
    private final String typeName;

    FakeSync(Map<String, Object> values) {
      this(values, "TestType");
    }

    FakeSync(Map<String, Object> values, String typeName) {
      this.values = values;
      this.typeName = typeName;
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
    public java.lang.Object fetch(
        String selection, kotlin.coroutines.Continuation<? super java.lang.Object> $completion) {
      return values.get(selection);
    }

    @Override
    public java.lang.Object fetchOrNull(
        String selection, kotlin.coroutines.Continuation<? super java.lang.Object> $completion) {
      return values.get(selection);
    }

    @Override
    public java.lang.Object fetchSelections(
        kotlin.coroutines.Continuation<? super Iterable<String>> $completion) {
      return values.keySet();
    }

    @Override
    public GraphQLObjectType getType() {
      return GraphQLObjectType.newObject().name(typeName).build();
    }
  }

  /** Fake NodeReference exposing only an id, mirroring the engine's unresolved-node contract. */
  static final class FakeNodeReference implements NodeReference {
    private final String id;

    FakeNodeReference(String id) {
      this.id = id;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public GraphQLObjectType getType() {
      return GraphQLObjectType.newObject().name("Node").build();
    }
  }

  /** Fake unresolved root field reference for construction-path and access tests. */
  static final class FakeRootFieldReference implements RootFieldReference {
    @Override
    public List<String> getRootFieldPath() {
      return List.of("_factories", "products", "create");
    }

    @Override
    public GraphQLObjectType getType() {
      return GraphQLObjectType.newObject().name("Product").build();
    }

    @Override
    public Map<String, Object> getArgs() {
      return Map.of("name", "Widget");
    }
  }

  static final class TestNode implements NodeCompositeOutput {}

  /** Fake InternalContext that deserializes a GlobalID by treating the raw string as the id. */
  static final class FakeContext implements InternalContext {
    @Override
    public viaduct.engine.api.ViaductSchema getSchema() {
      throw new UnsupportedOperationException();
    }

    @Override
    public viaduct.service.api.spi.GlobalIDCodec getGlobalIDCodec() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResolverClassFinder getClassFinder() {
      throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
      return (GlobalID<T>) new FakeGlobalID(serialized);
    }
  }

  static final class FakeGlobalID implements GlobalID<TestNode> {
    private final String internalId;

    FakeGlobalID(String internalId) {
      this.internalId = internalId;
    }

    @Override
    public Type<TestNode> getType() {
      return Type.ofClass(TestNode.class);
    }

    @Override
    public String getInternalID() {
      return internalId;
    }
  }

  // ===== Helpers =====

  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      m.put((String) keyValues[i], keyValues[i + 1]);
    }
    return m;
  }

  // ===== Construction paths / backing-data accessors =====

  @Test
  void enginePath_exposesEngineObjectDataAndNullsForOthers() {
    FakeSync sync = new FakeSync(map("name", "Alice"));
    TestObject obj = new TestObject(null, sync);

    assertSame(sync, obj.getJavaEngineObjectData());
    assertNull(obj.getJavaMapData());
    assertNull(obj.getJavaNodeReference());
    assertNull(obj.getJavaRootFieldReference());
  }

  @Test
  void builderPath_exposesUnmodifiableMapAndNullsForOthers() {
    TestObject obj = new TestObject(null, map("name", "Alice"));

    assertEquals("Alice", obj.getJavaMapData().get("name"));
    assertNull(obj.getJavaEngineObjectData());
    assertNull(obj.getJavaNodeReference());
    assertNull(obj.getJavaRootFieldReference());
  }

  @Test
  void builderPath_mapDataIsUnmodifiable() {
    TestObject obj = new TestObject(null, map("name", "Alice"));

    assertThrows(
        UnsupportedOperationException.class, () -> obj.getJavaMapData().put("name", "Bob"));
  }

  @Test
  void nodeReferencePath_exposesNodeReferenceAndNullsForOthers() {
    FakeNodeReference ref = new FakeNodeReference("User:1");
    TestObject obj = new TestObject(null, ref);

    assertSame(ref, obj.getJavaNodeReference());
    assertNull(obj.getJavaEngineObjectData());
    assertNull(obj.getJavaMapData());
    assertNull(obj.getJavaRootFieldReference());
  }

  @Test
  void rootFieldReferencePath_exposesReferenceAndNullsForOthers() {
    FakeRootFieldReference ref = new FakeRootFieldReference();
    TestObject obj = new TestObject(null, ref);

    assertSame(ref, obj.getJavaRootFieldReference());
    assertNull(obj.getJavaEngineObjectData());
    assertNull(obj.getJavaMapData());
    assertNull(obj.getJavaNodeReference());
  }

  // ===== fetchScalar =====

  @Test
  void fetchScalar_returnsValueFromEngineData() {
    TestObject obj = new TestObject(null, new FakeSync(map("name", "Alice")));

    assertEquals("Alice", obj.scalar("name"));
  }

  @Test
  void fetchScalar_returnsValueFromBuilderMap() {
    TestObject obj = new TestObject(null, map("name", "Alice"));

    assertEquals("Alice", obj.scalar("name"));
  }

  @Test
  void fetchScalar_returnsNullForMissingField() {
    TestObject obj = new TestObject(null, map("name", "Alice"));

    assertNull(obj.scalar("missing"));
  }

  @Test
  void fetchScalar_coercesDateTimeStringToInstant() {
    TestObject obj = new TestObject(null, map("createdAt", "2024-01-15T10:30:00+00:00"));

    assertEquals(Instant.parse("2024-01-15T10:30:00Z"), obj.scalar("createdAt", "DateTime"));
  }

  @Test
  void fetchScalar_cachesValueAcrossRepeatedReads() {
    Map<String, Object> backing = map("name", "Alice");
    TestObject obj = new TestObject(null, backing);

    String first = obj.scalar("name");
    backing.put("name", "Bob"); // mutate underlying map after first read
    String second = obj.scalar("name");

    assertEquals("Alice", first);
    assertEquals("Alice", second);
  }

  @Test
  void fetchScalar_onNodeReference_returnsIdForIdField() {
    TestObject obj = new TestObject(null, new FakeNodeReference("User:1"));

    assertEquals("User:1", obj.scalar("id"));
  }

  @Test
  void fetchScalar_onNodeReference_throwsForNonIdField() {
    TestObject obj = new TestObject(null, new FakeNodeReference("User:1"));

    FrameworkException e = assertThrows(FrameworkException.class, () -> obj.scalar("name"));
    assertTrue(e.getMessage().contains("only `id` is accessible"));
  }

  @Test
  void fetchScalar_onRootFieldReference_throwsForEveryField() {
    TestObject obj = new TestObject(null, new FakeRootFieldReference());

    FrameworkException e = assertThrows(FrameworkException.class, () -> obj.scalar("name"));
    assertTrue(e.getMessage().contains("ctx.rootFieldRef"));
  }

  // ===== fetchScalarList =====

  @Test
  void fetchScalarList_returnsListValue() {
    TestObject obj = new TestObject(null, map("tags", Arrays.asList("a", "b")));

    assertEquals(Arrays.asList("a", "b"), obj.scalarList("tags"));
  }

  @Test
  void fetchScalarList_returnsNullForMissingField() {
    TestObject obj = new TestObject(null, map());

    assertNull(obj.scalarList("tags"));
  }

  @Test
  void fetchScalarList_throwsWhenValueIsNotAList() {
    TestObject obj = new TestObject(null, map("tags", "not-a-list"));

    FrameworkException e = assertThrows(FrameworkException.class, () -> obj.scalarList("tags"));
    assertTrue(e.getMessage().contains("Expected List"));
  }

  @Test
  void fetchScalarList_coercesEachElementToLocalDate() {
    TestObject obj = new TestObject(null, map("dates", Arrays.asList("2024-01-15", "2024-02-20")));

    assertEquals(
        Arrays.asList(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 2, 20)),
        obj.scalarList("dates", "Date"));
  }

  // ===== fetchObject / fetchObjectList =====

  @Test
  void fetchObject_wrapsEngineDataWithConstructor() {
    FakeSync nested = new FakeSync(map("name", "Nested"));
    TestObject obj = new TestObject(null, new FakeSync(map("child", nested)));

    TestObject child = obj.object("child", TestObject::new);

    assertEquals("Nested", child.scalar("name"));
  }

  @Test
  void fetchObject_returnsBuilderInstanceAsIs() {
    TestObject nested = new TestObject(null, map("name", "Nested"));
    TestObject obj = new TestObject(null, map("child", nested));

    assertSame(nested, obj.object("child", TestObject::new));
  }

  @Test
  void fetchObject_returnsNullForMissingField() {
    TestObject obj = new TestObject(null, map());

    assertNull(obj.object("child", TestObject::new));
  }

  @Test
  void fetchObjectList_wrapsEachEngineDataElement() {
    FakeSync a = new FakeSync(map("name", "A"));
    FakeSync b = new FakeSync(map("name", "B"));
    TestObject obj = new TestObject(null, new FakeSync(map("children", Arrays.asList(a, b))));

    List<TestObject> children = obj.objectList("children", TestObject::new);

    assertEquals(2, children.size());
    assertEquals("A", children.get(0).scalar("name"));
    assertEquals("B", children.get(1).scalar("name"));
  }

  @Test
  void fetchObjectList_preservesNullElements() {
    List<Object> raw = new ArrayList<>();
    raw.add(new FakeSync(map("name", "A")));
    raw.add(null);
    TestObject obj = new TestObject(null, new FakeSync(map("children", raw)));

    List<TestObject> children = obj.objectList("children", TestObject::new);

    assertEquals("A", children.get(0).scalar("name"));
    assertNull(children.get(1));
  }

  // ===== fetchEnum / fetchEnumList =====

  @Test
  void fetchEnum_convertsStringNameToEnum() {
    TestObject obj = new TestObject(null, new FakeSync(map("color", "RED")));

    assertEquals(Color.RED, obj.enumValue("color", Color.class));
  }

  @Test
  void fetchEnum_returnsEnumInstanceAsIs() {
    TestObject obj = new TestObject(null, map("color", Color.GREEN));

    assertEquals(Color.GREEN, obj.enumValue("color", Color.class));
  }

  @Test
  void fetchEnumList_convertsMixedStringAndEnumElements() {
    TestObject obj = new TestObject(null, map("colors", Arrays.asList("RED", Color.GREEN)));

    assertEquals(Arrays.asList(Color.RED, Color.GREEN), obj.enumList("colors", Color.class));
  }

  // ===== fetchGlobalID / fetchGlobalIDList =====

  @Test
  void fetchGlobalID_deserializesStringViaContext() {
    TestObject obj = new TestObject(new FakeContext(), map("ownerId", "User:42"));

    GlobalID<TestNode> id = obj.globalId("ownerId");

    assertEquals("User:42", id.getInternalID());
  }

  @Test
  void fetchGlobalID_returnsNullForMissingField() {
    TestObject obj = new TestObject(new FakeContext(), map());

    assertNull(obj.globalId("ownerId"));
  }

  @Test
  void fetchGlobalIDList_deserializesEachElement() {
    TestObject obj =
        new TestObject(new FakeContext(), map("ids", Arrays.asList("User:1", "User:2")));

    List<GlobalID<TestNode>> ids = obj.globalIdList("ids");

    assertEquals("User:1", ids.get(0).getInternalID());
    assertEquals("User:2", ids.get(1).getInternalID());
  }

  @Test
  void fetchGlobalIDList_throwsWhenValueIsNotAList() {
    TestObject obj = new TestObject(new FakeContext(), map("ids", "not-a-list"));

    FrameworkException e = assertThrows(FrameworkException.class, () -> obj.globalIdList("ids"));
    assertTrue(e.getMessage().contains("Expected List"));
  }

  // ===== No backing data =====

  @Test
  void fetchScalar_throwsWhenNoBackingDataAndFieldRequested() {
    // Engine constructor with a null Sync leaves all backing slots empty.
    TestObject obj = new TestObject(null, (EngineObjectData.Sync) null);

    FrameworkException e = assertThrows(FrameworkException.class, () -> obj.scalar("name"));
    assertTrue(e.getMessage().contains("no backing data"));
  }

  // ===== __context propagation =====

  @Test
  void enginePath_propagatesContextToNestedObject() {
    FakeContext context = new FakeContext();
    FakeSync nested = new FakeSync(map("ownerId", "User:7"));
    TestObject obj = new TestObject(context, new FakeSync(map("child", nested)));

    TestObject child = obj.object("child", TestObject::new);

    // The nested GRT received the same context, so it can deserialize GlobalIDs.
    assertEquals("User:7", child.<TestNode>globalId("ownerId").getInternalID());
  }

  @Test
  void instantiateConcrete_failure_isWrappedAsFrameworkException() {
    // The interface package has no class named after the engine type, so reflection fails.
    FakeSync nested = new FakeSync(map(), "NoSuchConcreteType");
    TestAbstractHolder holder = new TestAbstractHolder(null, new FakeSync(map("thing", nested)));

    FrameworkException e =
        assertThrows(FrameworkException.class, () -> holder.abstractThing("thing"));
    assertTrue(e.getMessage().contains("Failed to instantiate concrete type"));
  }

  /** Exposes fetchAbstractObject so we can exercise the reflection-based instantiation path. */
  static final class TestAbstractHolder extends ObjectBase {
    TestAbstractHolder(@Nullable InternalContext context, EngineObjectData.Sync data) {
      super(context, data);
    }

    @Nullable Object abstractThing(String field) {
      return fetchAbstractObject(field, NodeCompositeOutput.class);
    }
  }
}
