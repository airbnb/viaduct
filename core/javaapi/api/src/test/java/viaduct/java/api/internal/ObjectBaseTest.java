package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
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
import viaduct.engine.api.EngineSchema;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.ResolvedEngineObjectData;
import viaduct.engine.api.RootFieldReference;
import viaduct.errors.FrameworkException;
import viaduct.errors.TenantUsageException;
import viaduct.errors.UnsetFieldException;
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

  private static final GraphQLScalarType BACKING_DATA =
      GraphQLScalarType.newScalar()
          .name("BackingData")
          .coercing(Scalars.GraphQLString.getCoercing())
          .build();

  // ===== Test doubles =====

  /** Minimal concrete GRT exposing ObjectBase's protected fetch* methods for testing. */
  static final class TestObject extends ObjectBase {
    TestObject(@Nullable InternalContext context, EngineObjectData.Sync data) {
      super(context, data);
    }

    TestObject(@Nullable InternalContext context, Map<String, Object> data) {
      super(context, data, "TestType");
    }

    private TestObject(InternalContext context, ObjectBase base, Map<String, Object> data) {
      super(context, base, data);
    }

    TestObject copy(Map<String, Object> data) {
      return new TestObject(__context(), toBuilderBase(), data);
    }

    TestObject(@Nullable InternalContext context, NodeReference ref) {
      super(context, ref);
    }

    TestObject(@Nullable InternalContext context, RootFieldReference ref) {
      super(context, ref);
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    <T> @Nullable T scalar(String field) {
      return fetchScalar(field, null);
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    <T> @Nullable T scalarAliased(String field, String alias) {
      return fetchScalar(field, alias);
    }

    /** The body a generated {@code getXxx()} accessor emits. */
    @Nullable Object softScalar(String field) {
      return nullOnDataFailure(() -> fetchScalar(field, null));
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    <T> @Nullable T scalar(String field, String scalarType) {
      return fetchScalar(field, null, scalarType);
    }

    <T> @Nullable List<T> scalarList(String field) {
      return fetchScalarList(field, null);
    }

    <T> @Nullable List<T> scalarList(String field, String scalarType) {
      return fetchScalarList(field, null, scalarType);
    }

    <T extends ObjectBase> @Nullable T object(
        String field,
        Class<T> objectClass,
        BiFunction<InternalContext, EngineObjectData.Sync, T> ctor) {
      return fetchObject(field, null, objectClass, ctor);
    }

    <T extends ObjectBase> @Nullable List<T> objectList(
        String field,
        Class<T> objectClass,
        BiFunction<InternalContext, EngineObjectData.Sync, T> ctor) {
      return fetchObjectList(field, null, objectClass, ctor);
    }

    <E extends Enum<E>> @Nullable E enumValue(String field, Class<E> type) {
      return fetchEnum(field, null, type);
    }

    <E extends Enum<E>> @Nullable List<E> enumList(String field, Class<E> type) {
      return fetchEnumList(field, null, type);
    }

    <T extends NodeCompositeOutput> @Nullable GlobalID<T> globalId(String field) {
      return fetchGlobalID(field, null);
    }

    <T extends NodeCompositeOutput> @Nullable List<GlobalID<T>> globalIdList(String field) {
      return fetchGlobalIDList(field, null);
    }
  }

  static final class OtherObject extends ObjectBase {
    OtherObject() {
      super(null, map(), null);
    }
  }

  enum Color {
    RED,
    GREEN
  }

  static class State {
    final int value;

    State(int value) {
      this.value = value;
    }
  }

  static final class SubState extends State {
    SubState(int value) {
      super(value);
    }
  }

  private static GraphQLObjectType typeWithField(String fieldName, GraphQLOutputType fieldType) {
    return GraphQLObjectType.newObject()
        .name("TestType")
        .field(GraphQLFieldDefinition.newFieldDefinition().name(fieldName).type(fieldType))
        .build();
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
    private static final EngineSchema SCHEMA =
        new EngineSchema(
            GraphQLSchema.newSchema()
                .query(
                    GraphQLObjectType.newObject()
                        .name("TestType")
                        .field(
                            GraphQLFieldDefinition.newFieldDefinition()
                                .name("name")
                                .type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                        .field(
                            GraphQLFieldDefinition.newFieldDefinition()
                                .name("nickname")
                                .type(Scalars.GraphQLString))
                        .build())
                .build());

    @Override
    public EngineSchema getSchema() {
      return SCHEMA;
    }

    @Override
    public graphql.schema.GraphQLInputObjectType getArgumentsInputType(
        String name, String containingTypeName, String fieldName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public viaduct.service.api.spi.GlobalIDCodec getGlobalIDCodec() {
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

  @Test
  void copyReadsUnmodifiedRawValuesWithOriginalContext() {
    TestObject original =
        new TestObject(
            new FakeContext(),
            engineData(
                Map.of(
                    "id", "node-1",
                    "time", "2026-09-08T10:00:00Z",
                    "nickname", "Alice")));
    original.scalar("time", "DateTime");
    TestObject copy = original.copy(map("nickname", null));
    TestObject chained = copy.copy(Map.of("other", "value"));

    assertEquals("node-1", chained.globalId("id").getInternalID());
    assertEquals(Instant.parse("2026-09-08T10:00:00Z"), chained.scalar("time", "DateTime"));
    assertNull(chained.scalar("nickname"));
    assertEquals("Alice", original.scalar("nickname"));
    assertEquals("value", chained.scalar("other"));
  }

  @Test
  void copyReadsAliasedSelectionsFromBaseObject() {
    TestObject original = new TestObject(null, engineData(map("alias", "Alice")));
    TestObject copy = original.copy(Map.of());

    assertEquals("Alice", copy.scalarAliased("name", "alias"));
  }

  @Test
  void copyPreservesBackingDataType() {
    State state = new State(7);
    TestObject original =
        new TestObject(
            null, engineData(typeWithField("state", BACKING_DATA), Map.of("state", state)));
    TestObject copy = original.copy(Map.of());

    assertSame(state, copy.get("state", State.class));
    assertThrows(TenantUsageException.class, () -> copy.get("state", String.class));
  }

  @Test
  void toBuilderRejectsUnresolvedReferences() {
    TestObject node = new TestObject(null, new FakeNodeReference("node-1"));
    TestObject root = new TestObject(null, new FakeRootFieldReference());

    assertEquals(
        "Cannot call toBuilder() on an unresolved NodeReference.",
        assertThrows(TenantUsageException.class, node::toBuilderBase).getMessage());
    assertEquals(
        "Cannot call toBuilder() on an unresolved RootFieldReference.",
        assertThrows(TenantUsageException.class, root::toBuilderBase).getMessage());
  }

  // ===== Helpers =====

  /**
   * Engine-path backing data. Uses the engine's own {@link ResolvedEngineObjectData} rather than a
   * hand-rolled fake so the strict-read contract under test is the real one.
   */
  private static EngineObjectData.Sync engineData(Map<String, Object> values) {
    return engineData("TestType", values);
  }

  private static EngineObjectData.Sync engineData(String typeName, Map<String, Object> values) {
    GraphQLObjectType type =
        "TestType".equals(typeName)
            ? FakeContext.SCHEMA.getSchema().getObjectType(typeName)
            : GraphQLObjectType.newObject().name(typeName).build();
    return engineData(type, values);
  }

  static EngineObjectData.Sync engineData(GraphQLObjectType type, Map<String, Object> values) {
    return new ResolvedEngineObjectData(type, values);
  }

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
    EngineObjectData.Sync sync = engineData(map("name", "Alice"));
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
    TestObject obj = new TestObject(null, engineData(map("name", "Alice")));

    assertEquals("Alice", obj.scalar("name"));
  }

  @Test
  void fetchScalar_returnsValueFromBuilderMap() {
    TestObject obj = new TestObject(null, map("name", "Alice"));

    assertEquals("Alice", obj.scalar("name"));
  }

  @Test
  void fetchScalar_returnsNullForNullValueInEngineData() {
    TestObject obj = new TestObject(null, engineData(map("nickname", null)));

    assertNull(obj.scalar("nickname"));
  }

  @Test
  void fetchScalar_throwsTenantUsageExceptionForNullValueInNonNullField() {
    TestObject obj = new TestObject(null, engineData(map("name", null)));

    TenantUsageException e = assertThrows(TenantUsageException.class, () -> obj.scalar("name"));
    assertTrue(e.getMessage().contains("String!"), e.getMessage());
  }

  @Test
  void softAccessor_throwsTenantUsageExceptionForNullValueInNonNullField() {
    TestObject obj = new TestObject(null, engineData(map("name", null)));

    assertThrows(TenantUsageException.class, () -> obj.softScalar("name"));
  }

  @Test
  void fetchScalar_throwsUnsetFieldForSelectionMissingFromEngineData() {
    TestObject obj = new TestObject(null, engineData(map("name", "Alice")));

    assertThrows(UnsetFieldException.class, () -> obj.scalar("nickname"));
  }

  @Test
  void fetchScalar_throwsUnsetFieldNamingTheTypeForFieldTheBuilderNeverSet() {
    TestObject obj = new TestObject(new FakeContext(), map());

    UnsetFieldException e = assertThrows(UnsetFieldException.class, () -> obj.scalar("name"));

    assertEquals("TestType", e.getTypeName());
    assertTrue(e.getMessage().contains("TestType.name"), e.getMessage());
  }

  @Test
  void fetchScalar_throwsWithoutTypeWhenBuilderGRTHasNoContext() {
    TestObject obj = new TestObject(null, map());

    TenantUsageException e = assertThrows(TenantUsageException.class, () -> obj.scalar("name"));
    assertTrue(e.getMessage().contains("was not set"), e.getMessage());
  }

  @Test
  void softAccessor_rethrowsUnsetFieldRatherThanReturningNull() {
    TestObject obj = new TestObject(new FakeContext(), map());

    assertThrows(UnsetFieldException.class, () -> obj.softScalar("name"));
  }

  @Test
  void softAccessor_returnsNullForNullValue() {
    TestObject obj = new TestObject(new FakeContext(), map("nickname", null));

    assertNull(obj.softScalar("nickname"));
  }

  @Test
  void fetchScalar_readsAliasedSelectionInsteadOfFieldName() {
    TestObject obj = new TestObject(null, engineData(map("name", "Alice", "shortName", "Ali")));

    assertEquals("Alice", obj.scalar("name"));
    assertEquals("Ali", obj.scalarAliased("name", "shortName"));
  }

  @Test
  void fetchScalar_cachesAliasedAndUnaliasedReadsSeparately() {
    Map<String, Object> backing = map("name", "Alice", "shortName", "Ali");
    TestObject obj = new TestObject(null, backing);

    assertEquals("Ali", obj.scalarAliased("name", "shortName"));
    assertEquals("Alice", obj.scalar("name"));
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
  void fetchScalar_onNodeReference_throwsUnsetFieldForNonIdField() {
    TestObject obj = new TestObject(null, new FakeNodeReference("User:1"));

    UnsetFieldException e = assertThrows(UnsetFieldException.class, () -> obj.scalar("name"));
    assertEquals("Node", e.getTypeName());
    assertTrue(e.getMessage().contains("ctx.ref"), e.getMessage());
  }

  @Test
  void fetchScalar_onRootFieldReference_throwsUnsetFieldForEveryField() {
    TestObject obj = new TestObject(null, new FakeRootFieldReference());

    UnsetFieldException e = assertThrows(UnsetFieldException.class, () -> obj.scalar("name"));
    assertEquals("Product", e.getTypeName());
    assertTrue(e.getMessage().contains("ctx.ref"), e.getMessage());
  }

  // ===== fetchScalarList =====

  @Test
  void fetchScalarList_returnsListValue() {
    TestObject obj = new TestObject(null, map("tags", Arrays.asList("a", "b")));

    assertEquals(Arrays.asList("a", "b"), obj.scalarList("tags"));
  }

  @Test
  void fetchScalarList_throwsTenantUsageExceptionForNullValueInNonNullElement() {
    TestObject obj =
        new TestObject(
            null,
            engineData(
                typeWithField(
                    "tags", GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))),
                map("tags", Arrays.asList("a", null))));

    TenantUsageException e = assertThrows(TenantUsageException.class, () -> obj.scalarList("tags"));
    assertTrue(e.getMessage().contains("String!"), e.getMessage());
  }

  @Test
  void fetchScalarList_throwsUnsetFieldForMissingField() {
    TestObject obj = new TestObject(new FakeContext(), map());

    assertThrows(UnsetFieldException.class, () -> obj.scalarList("tags"));
  }

  @Test
  void fetchScalarList_reportsInvalidListAsTenantUsageException() {
    TestObject obj = new TestObject(null, map("tags", "not-a-list"));

    TenantUsageException e = assertThrows(TenantUsageException.class, () -> obj.scalarList("tags"));
    assertTrue(e.getMessage().contains("non-list value"));
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
    EngineObjectData.Sync nested = engineData(map("name", "Nested"));
    TestObject obj = new TestObject(null, engineData(map("child", nested)));

    TestObject child = obj.object("child", TestObject.class, TestObject::new);

    assertEquals("Nested", child.scalar("name"));
  }

  @Test
  void fetchObject_returnsBuilderInstanceAsIs() {
    TestObject nested = new TestObject(null, map("name", "Nested"));
    TestObject obj = new TestObject(null, map("child", nested));

    assertSame(nested, obj.object("child", TestObject.class, TestObject::new));
  }

  @Test
  void fetchObject_rejectsWrongBuilderObjectBeforeReturning() {
    TestObject obj = new TestObject(null, map("child", new OtherObject()));

    assertThrows(
        FrameworkException.class, () -> obj.object("child", TestObject.class, TestObject::new));
  }

  @Test
  void fetchObject_throwsUnsetFieldForMissingField() {
    TestObject obj = new TestObject(new FakeContext(), map());

    assertThrows(
        UnsetFieldException.class, () -> obj.object("child", TestObject.class, TestObject::new));
  }

  @Test
  void fetchObject_reportsInvalidValueAsTenantUsageException() {
    TestObject obj = new TestObject(null, map("child", "not-an-object"));

    TenantUsageException e =
        assertThrows(
            TenantUsageException.class,
            () -> obj.object("child", TestObject.class, TestObject::new));
    assertTrue(e.getMessage().contains("EngineObjectData"));
  }

  @Test
  void fetchObjectList_wrapsEachEngineDataElement() {
    EngineObjectData.Sync a = engineData(map("name", "A"));
    EngineObjectData.Sync b = engineData(map("name", "B"));
    TestObject obj = new TestObject(null, engineData(map("children", Arrays.asList(a, b))));

    List<TestObject> children = obj.objectList("children", TestObject.class, TestObject::new);

    assertEquals(2, children.size());
    assertEquals("A", children.get(0).scalar("name"));
    assertEquals("B", children.get(1).scalar("name"));
  }

  @Test
  void fetchObjectList_preservesNullElements() {
    List<Object> raw = new ArrayList<>();
    raw.add(engineData(map("name", "A")));
    raw.add(null);
    TestObject obj = new TestObject(null, engineData(map("children", raw)));

    List<TestObject> children = obj.objectList("children", TestObject.class, TestObject::new);

    assertEquals("A", children.get(0).scalar("name"));
    assertNull(children.get(1));
  }

  @Test
  void fetchObjectList_reportsInvalidValueAsTenantUsageException() {
    TestObject obj = new TestObject(null, map("children", "not-a-list"));

    TenantUsageException e =
        assertThrows(
            TenantUsageException.class,
            () -> obj.objectList("children", TestObject.class, TestObject::new));
    assertTrue(e.getMessage().contains("non-list value"));
  }

  // ===== fetchEnum / fetchEnumList =====

  @Test
  void fetchEnum_convertsStringNameToEnum() {
    TestObject obj = new TestObject(null, engineData(map("color", "RED")));

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

  @Test
  void fetchEnumList_reportsInvalidValueAsTenantUsageException() {
    TestObject obj = new TestObject(null, map("colors", "not-a-list"));

    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> obj.enumList("colors", Color.class));
    assertTrue(e.getMessage().contains("non-list value"));
  }

  // ===== fetchGlobalID / fetchGlobalIDList =====

  @Test
  void fetchGlobalID_deserializesStringViaContext() {
    TestObject obj = new TestObject(new FakeContext(), map("ownerId", "User:42"));

    GlobalID<TestNode> id = obj.globalId("ownerId");

    assertEquals("User:42", id.getInternalID());
  }

  @Test
  void fetchGlobalID_throwsUnsetFieldForMissingField() {
    TestObject obj = new TestObject(new FakeContext(), map());

    assertThrows(UnsetFieldException.class, () -> obj.globalId("ownerId"));
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

    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> obj.globalIdList("ids"));
    assertTrue(e.getMessage().contains("non-list value"));
  }

  @Test
  void dynamicGetter_returnsBackingDataValue() {
    State state = new State(7);
    TestObject obj =
        new TestObject(null, engineData(typeWithField("state", BACKING_DATA), map("state", state)));

    assertSame(state, obj.get("state", State.class));
  }

  @Test
  void dynamicGetter_checksCachedValueAgainstEveryClassToken() {
    State state = new State(7);
    TestObject obj =
        new TestObject(null, engineData(typeWithField("state", BACKING_DATA), map("state", state)));

    assertSame(state, obj.get("state", State.class));
    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> obj.get("state", Color.class));
    assertTrue(e.getMessage().contains("to be of type Color, got State"), e.getMessage());
  }

  @Test
  void dynamicGetter_matchesKotlinExactClassContract() {
    SubState state = new SubState(7);
    TestObject obj =
        new TestObject(null, engineData(typeWithField("state", BACKING_DATA), map("state", state)));

    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> obj.get("state", State.class));
    assertTrue(e.getMessage().contains("to be of type State, got SubState"), e.getMessage());
  }

  @Test
  void dynamicGetter_checksBackingDataListsAndPreservesNulls() {
    State state = new State(7);
    List<State> source = Arrays.asList(state, null);
    TestObject obj =
        new TestObject(
            null,
            engineData(
                typeWithField("states", GraphQLList.list(BACKING_DATA)), map("states", source)));

    List<State> result = obj.get("states", State.class);

    assertSame(source, result);
    assertSame(state, result.get(0));
    assertNull(result.get(1));
  }

  @Test
  void dynamicGetter_supportsNestedBackingDataLists() {
    State state = new State(7);
    List<List<State>> source = List.of(List.of(state));
    TestObject obj =
        new TestObject(
            null,
            engineData(
                typeWithField("states", GraphQLList.list(GraphQLList.list(BACKING_DATA))),
                map("states", source)));

    List<List<State>> result = obj.get("states", State.class);

    assertSame(source, result);
    assertSame(state, result.get(0).get(0));
  }

  @Test
  void dynamicGetter_rejectsNonListValueForListField() {
    TestObject obj =
        new TestObject(
            null,
            engineData(
                typeWithField("states", GraphQLList.list(BACKING_DATA)),
                map("states", new State(7))));

    FrameworkException e =
        assertThrows(FrameworkException.class, () -> obj.get("states", State.class));
    assertTrue(e.getMessage().contains("Expected List for field 'states'"), e.getMessage());
  }

  @Test
  void dynamicGetter_rejectsNullForNonNullBackingData() {
    TestObject obj =
        new TestObject(
            null,
            engineData(
                typeWithField("state", GraphQLNonNull.nonNull(BACKING_DATA)), map("state", null)));

    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> obj.get("state", State.class));
    assertTrue(e.getMessage().contains("Got null backing data value"), e.getMessage());
  }

  @Test
  void dynamicGetter_rejectsNonBackingDataFields() {
    TestObject obj =
        new TestObject(
            null, engineData(typeWithField("name", Scalars.GraphQLString), map("name", "Ada")));

    FrameworkException e =
        assertThrows(FrameworkException.class, () -> obj.get("name", String.class));
    assertTrue(e.getMessage().contains("cannot read field 'name'"), e.getMessage());
  }

  @Test
  void dynamicGetter_rejectsNullNonBackingDataFields() {
    TestObject obj =
        new TestObject(
            null, engineData(typeWithField("name", Scalars.GraphQLString), map("name", null)));

    FrameworkException e =
        assertThrows(FrameworkException.class, () -> obj.get("name", String.class));
    assertTrue(e.getMessage().contains("cannot read field 'name'"), e.getMessage());
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
    EngineObjectData.Sync nested = engineData(map("ownerId", "User:7"));
    TestObject obj = new TestObject(context, engineData(map("child", nested)));

    TestObject child = obj.object("child", TestObject.class, TestObject::new);

    // The nested GRT received the same context, so it can deserialize GlobalIDs.
    assertEquals("User:7", child.<TestNode>globalId("ownerId").getInternalID());
  }

  @Test
  void instantiateConcrete_failure_isWrappedAsFrameworkException() {
    // The interface package has no class named after the engine type, so reflection fails.
    EngineObjectData.Sync nested = engineData("NoSuchConcreteType", map());
    TestAbstractHolder holder = new TestAbstractHolder(null, engineData(map("thing", nested)));

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
      return fetchAbstractObject(field, null, NodeCompositeOutput.class);
    }
  }
}
