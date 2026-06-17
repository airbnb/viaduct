package viaduct.java.api.reflect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import viaduct.java.api.types.GRT;

class TypeTest {

  static class TestGRT implements GRT {}

  static class AnotherTestGRT implements GRT {}

  static class NotAGRT {}

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
    assertTrue(e.getMessage().contains("Class must implement GRT"));
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
}
