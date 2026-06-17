package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.errors.TenantResolverException;
import viaduct.errors.TenantUsageException;

/** Tests for the Java-facing {@link HandleErrors} Callable overloads. */
class HandleErrorsTest {

  // ── framework() ───────────────────────────────────────────────────────────────

  @Test
  void framework_returnsValueFromBlock() {
    String result = HandleErrors.framework("op", () -> "hello");
    assertEquals("hello", result);
  }

  @Test
  void framework_passesThroughFrameworkException() {
    FrameworkException original = new FrameworkException("inner", null);
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                HandleErrors.framework(
                    "op",
                    () -> {
                      throw original;
                    }));
    assertSame(original, e);
  }

  @Test
  void framework_passesThroughTenantException() {
    TenantUsageException original = new TenantUsageException("tenant bug", null);
    TenantUsageException e =
        assertThrows(
            TenantUsageException.class,
            () ->
                HandleErrors.framework(
                    "op",
                    () -> {
                      throw original;
                    }));
    assertSame(original, e);
  }

  @Test
  void framework_wrapsGenericExceptionAsFrameworkException() {
    IllegalArgumentException boom = new IllegalArgumentException("boom");
    FrameworkException e =
        assertThrows(
            FrameworkException.class,
            () ->
                HandleErrors.framework(
                    "myOp",
                    () -> {
                      throw boom;
                    }));
    assertTrue(e.getMessage().contains("myOp"));
    assertTrue(e.getMessage().contains("boom"));
    assertEquals(boom, e.getCause());
  }

  // ── tenant() ──────────────────────────────────────────────────────────────────

  @Test
  void tenant_returnsValueFromBlock() {
    String result = HandleErrors.tenant("resolver", () -> "hello");
    assertEquals("hello", result);
  }

  @Test
  void tenant_passesThroughPassthroughException() {
    FrameworkException original = new FrameworkException("framework bug", null);
    Exception e =
        assertThrows(
            Exception.class,
            () ->
                HandleErrors.tenant(
                    "resolver",
                    () -> {
                      throw original;
                    }));
    assertSame(original, e);
  }

  @Test
  void tenant_wrapsGenericExceptionAsTenantResolverException() {
    IllegalArgumentException boom = new IllegalArgumentException("boom");
    TenantResolverException e =
        assertThrows(
            TenantResolverException.class,
            () ->
                HandleErrors.tenant(
                    "MyResolver",
                    () -> {
                      throw boom;
                    }));
    assertEquals(boom, e.getCause());
    assertEquals("MyResolver", e.getResolver());
  }
}
