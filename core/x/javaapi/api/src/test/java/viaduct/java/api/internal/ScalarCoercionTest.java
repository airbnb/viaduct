package viaduct.java.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import viaduct.errors.FrameworkException;

/** Unit tests for {@link JavaObjectBase#coerceScalar}. */
class ScalarCoercionTest {

  // ===== Null handling =====

  @Test
  void nullRawValue_returnsNull() {
    assertThat(JavaObjectBase.coerceScalar(null, "DateTime")).isNull();
  }

  @Test
  void nullScalarType_returnsRawValue() {
    assertThat(JavaObjectBase.coerceScalar("hello", null)).isEqualTo("hello");
  }

  @Test
  void bothNull_returnsNull() {
    assertThat(JavaObjectBase.coerceScalar(null, null)).isNull();
  }

  // ===== DateTime → Instant =====

  @Test
  void dateTime_stringToInstant() {
    Object result = JavaObjectBase.coerceScalar("2024-01-15T10:30:00+00:00", "DateTime");
    assertThat(result).isInstanceOf(Instant.class);
    assertThat(result).isEqualTo(Instant.parse("2024-01-15T10:30:00Z"));
  }

  @Test
  void dateTime_stringWithOffsetToInstant() {
    Object result = JavaObjectBase.coerceScalar("2024-06-15T12:00:00+05:30", "DateTime");
    assertThat(result).isInstanceOf(Instant.class);
    assertThat(result).isEqualTo(Instant.parse("2024-06-15T06:30:00Z"));
  }

  @Test
  void dateTime_instantPassThrough() {
    Instant instant = Instant.parse("2024-01-15T10:30:00Z");
    Object result = JavaObjectBase.coerceScalar(instant, "DateTime");
    assertThat(result).isSameAs(instant);
  }

  @Test
  void dateTime_invalidFormat_throwsFrameworkException() {
    assertThatThrownBy(() -> JavaObjectBase.coerceScalar("not-a-date", "DateTime"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void dateTime_unsupportedType_throwsFrameworkException() {
    assertThatThrownBy(() -> JavaObjectBase.coerceScalar(12345, "DateTime"))
        .isInstanceOf(FrameworkException.class)
        .hasMessageContaining("Could not convert");
  }

  // ===== Date → LocalDate =====

  @Test
  void date_stringToLocalDate() {
    Object result = JavaObjectBase.coerceScalar("2024-01-15", "Date");
    assertThat(result).isInstanceOf(LocalDate.class);
    assertThat(result).isEqualTo(LocalDate.of(2024, 1, 15));
  }

  @Test
  void date_localDatePassThrough() {
    LocalDate date = LocalDate.of(2024, 1, 15);
    Object result = JavaObjectBase.coerceScalar(date, "Date");
    assertThat(result).isSameAs(date);
  }

  @Test
  void date_unsupportedType_throwsFrameworkException() {
    assertThatThrownBy(() -> JavaObjectBase.coerceScalar(12345, "Date"))
        .isInstanceOf(FrameworkException.class)
        .hasMessageContaining("Could not convert");
  }

  // ===== Time → OffsetTime =====

  @Test
  void time_stringToOffsetTime() {
    Object result = JavaObjectBase.coerceScalar("14:30:00+00:00", "Time");
    assertThat(result).isInstanceOf(OffsetTime.class);
    assertThat(result).isEqualTo(OffsetTime.of(14, 30, 0, 0, ZoneOffset.UTC));
  }

  @Test
  void time_offsetTimePassThrough() {
    OffsetTime time = OffsetTime.of(14, 30, 0, 0, ZoneOffset.UTC);
    Object result = JavaObjectBase.coerceScalar(time, "Time");
    assertThat(result).isSameAs(time);
  }

  @Test
  void time_unsupportedType_throwsFrameworkException() {
    assertThatThrownBy(() -> JavaObjectBase.coerceScalar(12345, "Time"))
        .isInstanceOf(FrameworkException.class)
        .hasMessageContaining("Could not convert");
  }

  // ===== Unknown scalar type =====

  @Test
  void unknownScalarType_returnsRawValue() {
    assertThat(JavaObjectBase.coerceScalar("hello", "String")).isEqualTo("hello");
    assertThat(JavaObjectBase.coerceScalar(42, "Int")).isEqualTo(42);
  }
}
