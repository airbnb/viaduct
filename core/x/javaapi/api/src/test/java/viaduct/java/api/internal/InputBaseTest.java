package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import graphql.schema.GraphQLInputObjectType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import viaduct.errors.FrameworkException;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * Unit tests for {@link InputBase}.
 *
 * <p>{@link InputBase} is an abstract base class for input-type GRTs, so it is exercised through a
 * minimal concrete subclass that exposes the {@code protected} get* methods. Tests assert on the
 * values returned (state-based), covering scalar access, scalar coercion, list/nested-input/enum
 * access, and GlobalID deserialization including its failure mode.
 */
class InputBaseTest {

  // ===== Test doubles =====

  /** Minimal concrete input GRT exposing InputBase's protected get* methods for testing. */
  static final class TestInput extends InputBase {
    TestInput(@Nullable InternalContext context, Map<String, Object> data) {
      super(context, data, null);
    }

    TestInput(
        @Nullable InternalContext context,
        Map<String, Object> data,
        @Nullable GraphQLInputObjectType type) {
      super(context, data, type);
    }

    <T> @Nullable T scalar(String field) {
      return get(field);
    }

    <T> @Nullable T scalar(String field, String scalarType) {
      return get(field, scalarType);
    }

    <T> @Nullable List<T> scalarList(String field) {
      return getScalarList(field);
    }

    <T> @Nullable List<T> scalarList(String field, String scalarType) {
      return getScalarList(field, scalarType);
    }

    <T extends InputBase> @Nullable T input(String field, InputConstructor<T> ctor) {
      return getInput(field, ctor);
    }

    <T extends InputBase> @Nullable List<T> inputList(String field, InputConstructor<T> ctor) {
      return getInputList(field, ctor);
    }

    <E extends Enum<E>> @Nullable E enumValue(String field, Class<E> type) {
      return getEnum(field, type);
    }

    <E extends Enum<E>> @Nullable List<E> enumList(String field, Class<E> type) {
      return getEnumList(field, type);
    }

    <T extends NodeCompositeOutput> @Nullable GlobalID<T> globalId(String field) {
      return getGlobalID(field);
    }

    <T extends NodeCompositeOutput> @Nullable List<GlobalID<T>> globalIdList(String field) {
      return getGlobalIDList(field);
    }
  }

  enum Color {
    RED,
    GREEN
  }

  static final class TestNode implements NodeCompositeOutput {}

  /** Fake InternalContext whose codec treats the raw string as the internal id. */
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
      if ("bad".equals(serialized)) {
        throw new IllegalArgumentException("malformed global id");
      }
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

  // ===== Backing-data accessor =====

  @Test
  void getInputData_returnsUnmodifiableView() {
    TestInput input = new TestInput(null, map("name", "Alice"));

    assertEquals("Alice", input.getInputData().get("name"));
    assertThrows(
        UnsupportedOperationException.class, () -> input.getInputData().put("name", "Bob"));
  }

  // ===== get (scalar) =====

  @Test
  void get_returnsScalarValue() {
    TestInput input = new TestInput(null, map("name", "Alice"));

    assertEquals("Alice", input.scalar("name"));
  }

  @Test
  void get_returnsNullForMissingField() {
    TestInput input = new TestInput(null, map());

    assertNull(input.scalar("name"));
  }

  @Test
  void get_coercesDateTimeStringToInstant() {
    TestInput input = new TestInput(null, map("createdAt", "2024-01-15T10:30:00+00:00"));

    assertEquals(Instant.parse("2024-01-15T10:30:00Z"), input.scalar("createdAt", "DateTime"));
  }

  // ===== getScalarList =====

  @Test
  void getScalarList_returnsListValue() {
    TestInput input = new TestInput(null, map("tags", Arrays.asList("a", "b")));

    assertEquals(Arrays.asList("a", "b"), input.scalarList("tags"));
  }

  @Test
  void getScalarList_returnsNullForMissingField() {
    TestInput input = new TestInput(null, map());

    assertNull(input.scalarList("tags"));
  }

  @Test
  void getScalarList_throwsWhenValueIsNotAList() {
    TestInput input = new TestInput(null, map("tags", "not-a-list"));

    FrameworkException e = assertThrows(FrameworkException.class, () -> input.scalarList("tags"));
    assertTrue(e.getMessage().contains("Expected List"));
  }

  @Test
  void getScalarList_coercesEachElementToLocalDate() {
    TestInput input = new TestInput(null, map("dates", Arrays.asList("2024-01-15", "2024-02-20")));

    assertEquals(
        Arrays.asList(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 2, 20)),
        input.scalarList("dates", "Date"));
  }

  // ===== getInput / getInputList =====

  @Test
  void getInput_wrapsNestedMapWithConstructor() {
    TestInput input = new TestInput(null, map("nested", map("name", "Nested")));

    TestInput nested = input.input("nested", TestInput::new);

    assertEquals("Nested", nested.scalar("name"));
  }

  @Test
  void getInput_returnsExistingInputBaseAsIs() {
    TestInput nested = new TestInput(null, map("name", "Nested"));
    TestInput input = new TestInput(null, map("nested", nested));

    assertSame(nested, input.input("nested", TestInput::new));
  }

  @Test
  void getInput_returnsNullForMissingField() {
    TestInput input = new TestInput(null, map());

    assertNull(input.input("nested", TestInput::new));
  }

  @Test
  void getInputList_wrapsEachMapAndPreservesNulls() {
    List<Object> raw = new ArrayList<>();
    raw.add(map("name", "A"));
    raw.add(null);
    TestInput input = new TestInput(null, map("items", raw));

    List<TestInput> items = input.inputList("items", TestInput::new);

    assertEquals("A", items.get(0).scalar("name"));
    assertNull(items.get(1));
  }

  // ===== getEnum / getEnumList =====

  @Test
  void getEnum_convertsStringNameToEnum() {
    TestInput input = new TestInput(null, map("color", "RED"));

    assertEquals(Color.RED, input.enumValue("color", Color.class));
  }

  @Test
  void getEnum_returnsEnumInstanceAsIs() {
    TestInput input = new TestInput(null, map("color", Color.GREEN));

    assertEquals(Color.GREEN, input.enumValue("color", Color.class));
  }

  @Test
  void getEnum_returnsNullForMissingField() {
    TestInput input = new TestInput(null, map());

    assertNull(input.enumValue("color", Color.class));
  }

  @Test
  void getEnumList_convertsMixedStringAndEnumElements() {
    TestInput input = new TestInput(null, map("colors", Arrays.asList("RED", Color.GREEN)));

    assertEquals(Arrays.asList(Color.RED, Color.GREEN), input.enumList("colors", Color.class));
  }

  // ===== getGlobalID / getGlobalIDList =====

  @Test
  void getGlobalID_deserializesStringViaContext() {
    TestInput input = new TestInput(new FakeContext(), map("ownerId", "User:42"));

    GlobalID<TestNode> id = input.globalId("ownerId");

    assertEquals("User:42", id.getInternalID());
  }

  @Test
  void getGlobalID_returnsNullForMissingField() {
    TestInput input = new TestInput(new FakeContext(), map());

    assertNull(input.globalId("ownerId"));
  }

  @Test
  void getGlobalID_throwsTenantUsageExceptionWhenDeserializationFails() {
    TestInput input = new TestInput(new FakeContext(), map("ownerId", "bad"));

    TenantUsageException e =
        assertThrows(TenantUsageException.class, () -> input.globalId("ownerId"));
    assertTrue(e.getMessage().contains("Invalid GlobalID"));
  }

  @Test
  void getGlobalIDList_deserializesEachElement() {
    TestInput input =
        new TestInput(new FakeContext(), map("ids", Arrays.asList("User:1", "User:2")));

    List<GlobalID<TestNode>> ids = input.globalIdList("ids");

    assertEquals("User:1", ids.get(0).getInternalID());
    assertEquals("User:2", ids.get(1).getInternalID());
  }

  @Test
  void getGlobalIDList_throwsWhenValueIsNotAList() {
    TestInput input = new TestInput(new FakeContext(), map("ids", "not-a-list"));

    FrameworkException e = assertThrows(FrameworkException.class, () -> input.globalIdList("ids"));
    assertTrue(e.getMessage().contains("Expected List"));
  }
}
