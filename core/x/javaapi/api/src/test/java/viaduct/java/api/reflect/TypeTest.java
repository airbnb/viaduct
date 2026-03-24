package viaduct.java.api.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import viaduct.java.api.types.GRT;

class TypeTest {

  static class TestGRT implements GRT {}

  static class AnotherTestGRT implements GRT {}

  static class NotAGRT {}

  @Test
  void ofClass_returnsTypeWithCorrectName() {
    var type = Type.ofClass(TestGRT.class);

    assertThat(type.getName()).isEqualTo("TestGRT");
  }

  @Test
  void ofClass_returnsTypeWithCorrectJavaClass() {
    var type = Type.ofClass(TestGRT.class);

    assertThat(type.getJavaClass()).isEqualTo(TestGRT.class);
  }

  @Test
  void ofClass_throwsIllegalArgumentException_whenClassDoesNotImplementGRT() {
    //noinspection unchecked
    assertThatThrownBy(() -> Type.ofClass((Class<GRT>) (Class<?>) NotAGRT.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Class must implement GRT");
  }

  @Test
  void equals_returnsTrueForSameType() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(TestGRT.class);

    assertThat(type1).isEqualTo(type2);
  }

  @Test
  void equals_returnsFalseForDifferentTypes() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(AnotherTestGRT.class);

    //noinspection AssertBetweenInconvertibleTypes
    assertThat(type1).isNotEqualTo(type2);
  }

  @Test
  void equals_returnsFalseForNonTypeObject() {
    var type = Type.ofClass(TestGRT.class);

    //noinspection AssertBetweenInconvertibleTypes
    assertThat(type).isNotEqualTo("not a type");
    assertThat(type).isNotEqualTo(null);
  }

  @Test
  void hashCode_isConsistentWithEquals() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(TestGRT.class);

    assertThat(type1.hashCode()).isEqualTo(type2.hashCode());
  }

  @Test
  void hashCode_isDifferentForDifferentTypes() {
    var type1 = Type.ofClass(TestGRT.class);
    var type2 = Type.ofClass(AnotherTestGRT.class);

    assertThat(type1.hashCode()).isNotEqualTo(type2.hashCode());
  }

  @Test
  void toString_returnsExpectedFormat() {
    var type = Type.ofClass(TestGRT.class);

    assertThat(type.toString()).isEqualTo("Type(TestGRT)");
  }

  @Test
  void ofClass_worksWithGRTInterface() {
    var type = Type.ofClass(GRT.class);

    assertThat(type.getName()).isEqualTo("GRT");
    assertThat(type.getJavaClass()).isEqualTo(GRT.class);
  }
}
