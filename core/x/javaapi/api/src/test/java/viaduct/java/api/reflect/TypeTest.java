package viaduct.java.api.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.GRT;
import viaduct.java.api.types.GraphQLObject;

class TypeTest {

  static class TestGRT implements GRT {}

  static class AnotherTestGRT implements GRT {}

  static class NotAGRT {}

  static class TestObject implements GraphQLObject {}

  static class TestArguments implements Arguments {}

  @Test
  void ofClass_returnsTypeWithCorrectName() {
    var type = Type.ofClass(TestGRT.class);

    assertEquals("TestGRT", type.getName());
  }

  @Test
  void ofClass_returnsTypeWithCorrectJavaClass() {
    var type = Type.ofClass(TestGRT.class);

    assertEquals(TestGRT.class, type.getJavaClass());
  }

  @Test
  void ofClass_throwsIllegalArgumentException_whenClassDoesNotImplementGRT() {
    //noinspection unchecked
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> Type.ofClass((Class<GRT>) (Class<?>) NotAGRT.class));
    String message = e.getMessage();
    assertNotNull(message);
    assertTrue(message.contains("Class must implement GRT"));
  }

  @Test
  void equals_returnsTrueForSameType() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(TestGRT.class);

    assertEquals(type1, type2);
  }

  @Test
  void equals_returnsFalseForDifferentTypes() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(AnotherTestGRT.class);

    //noinspection AssertBetweenInconvertibleTypes
    assertNotEquals(type1, type2);
  }

  @Test
  void equals_returnsFalseForNonTypeObject() {
    var type = Type.ofClass(TestGRT.class);

    //noinspection AssertBetweenInconvertibleTypes
    assertNotEquals(type, "not a type");
    assertNotEquals(type, null);
  }

  @Test
  void hashCode_isConsistentWithEquals() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(TestGRT.class);

    assertEquals(type1.hashCode(), type2.hashCode());
  }

  @Test
  void hashCode_isDifferentForDifferentTypes() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(AnotherTestGRT.class);

    assertNotEquals(type1.hashCode(), type2.hashCode());
  }

  @Test
  void toString_returnsExpectedFormat() {
    var type = Type.ofClass(TestGRT.class);

    assertEquals("Type(TestGRT)", type.toString());
  }

  @Test
  void ofClass_worksWithGRTInterface() {
    var type = Type.ofClass(GRT.class);

    assertEquals("GRT", type.getName());
    assertEquals(GRT.class, type.getJavaClass());
  }

  @Test
  void fieldDescriptor_exposesNameAndContainingType() {
    Type<TestGRT> containingType = Type.ofClass(TestGRT.class);

    Field<TestGRT> field = Field.of("name", containingType);

    assertEquals("name", field.getName());
    assertEquals(containingType, field.getContainingType());
  }

  @Test
  void compositeFieldDescriptor_exposesUnwrappedType() {
    Type<TestGRT> containingType = Type.ofClass(TestGRT.class);
    Type<TestObject> fieldType = Type.ofClass(TestObject.class);

    CompositeField<TestGRT, TestObject> field =
        CompositeField.of("object", containingType, fieldType);

    assertEquals(fieldType, field.getType());
  }

  @Test
  void rootObjectFieldDescriptor_copiesAndValidatesPath() {
    Type<TestGRT> containingType = Type.ofClass(TestGRT.class);
    Type<TestObject> fieldType = Type.ofClass(TestObject.class);
    List<String> path = new java.util.ArrayList<>(List.of("namespace", "object"));

    RootObjectField<TestGRT, TestObject, TestArguments> field =
        RootObjectField.of("object", containingType, fieldType, path);
    path.clear();

    assertEquals(List.of("namespace", "object"), field.getPathFromQueryRoot());
    assertThrows(
        IllegalArgumentException.class,
        () -> RootObjectField.of("object", containingType, fieldType, List.of("other")));
  }
}
