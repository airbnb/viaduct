package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import viaduct.errors.FrameworkException;

/** Unit tests for {@link ObjectBase#coerceScalar}. */
class ScalarCoercionTest {

  // ===== Null handling =====

  @Test
  void nullRawValue_returnsNull() {
    assertNull(ObjectBase.coerceScalar(null, "DateTime"));
  }

  @Test
  void nullScalarType_returnsRawValue() {
    assertEquals("hello", ObjectBase.coerceScalar("hello", null));
  }

  @Test
  void bothNull_returnsNull() {
    assertNull(ObjectBase.coerceScalar(null, null));
  }

  // ===== DateTime → Instant =====

  @Test
  void dateTime_stringToInstant() {
    Object result = ObjectBase.coerceScalar("2024-01-15T10:30:00+00:00", "DateTime");
    assertInstanceOf(Instant.class, result);
    assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result);
  }

  @Test
  void dateTime_stringWithOffsetToInstant() {
    Object result = ObjectBase.coerceScalar("2024-06-15T12:00:00+05:30", "DateTime");
    assertInstanceOf(Instant.class, result);
    assertEquals(Instant.parse("2024-06-15T06:30:00Z"), result);
  }

  @Test
  void dateTime_instantPassThrough() {
    Instant instant = Instant.parse("2024-01-15T10:30:00Z");
    Object result = ObjectBase.coerceScalar(instant, "DateTime");
    assertSame(instant, result);
  }

  @Test
  void dateTime_invalidFormat_throwsFrameworkException() {
    assertThrows(Exception.class, () -> ObjectBase.coerceScalar("not-a-date", "DateTime"));
  }

  @Test
  void dateTime_unsupportedType_throwsFrameworkException() {
    FrameworkException e =
        assertThrows(FrameworkException.class, () -> ObjectBase.coerceScalar(12345, "DateTime"));
    assertTrue(e.getMessage().contains("Could not convert"));
  }

  // ===== Date → LocalDate =====

  @Test
  void date_stringToLocalDate() {
    Object result = ObjectBase.coerceScalar("2024-01-15", "Date");
    assertInstanceOf(LocalDate.class, result);
    assertEquals(LocalDate.of(2024, 1, 15), result);
  }

  @Test
  void date_localDatePassThrough() {
    LocalDate date = LocalDate.of(2024, 1, 15);
    Object result = ObjectBase.coerceScalar(date, "Date");
    assertSame(date, result);
  }

  @Test
  void date_unsupportedType_throwsFrameworkException() {
    FrameworkException e =
        assertThrows(FrameworkException.class, () -> ObjectBase.coerceScalar(12345, "Date"));
    assertTrue(e.getMessage().contains("Could not convert"));
  }

  // ===== Time → OffsetTime =====

  @Test
  void time_stringToOffsetTime() {
    Object result = ObjectBase.coerceScalar("14:30:00+00:00", "Time");
    assertInstanceOf(OffsetTime.class, result);
    assertEquals(OffsetTime.of(14, 30, 0, 0, ZoneOffset.UTC), result);
  }

  @Test
  void time_offsetTimePassThrough() {
    OffsetTime time = OffsetTime.of(14, 30, 0, 0, ZoneOffset.UTC);
    Object result = ObjectBase.coerceScalar(time, "Time");
    assertSame(time, result);
  }

  @Test
  void time_unsupportedType_throwsFrameworkException() {
    FrameworkException e =
        assertThrows(FrameworkException.class, () -> ObjectBase.coerceScalar(12345, "Time"));
    assertTrue(e.getMessage().contains("Could not convert"));
  }

  // ===== Unknown scalar type =====

  @Test
  void unknownScalarType_returnsRawValue() {
    assertEquals("hello", ObjectBase.coerceScalar("hello", "String"));
    assertEquals(42, ObjectBase.coerceScalar(42, "Int"));
  }
}
