package viaduct.java.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    assertThat(result).isEqualTo("hello");
  }

  @Test
  void framework_passesThroughFrameworkException() {
    FrameworkException original = new FrameworkException("inner", null);
    assertThatThrownBy(
            () ->
                HandleErrors.framework(
                    "op",
                    () -> {
                      throw original;
                    }))
        .isSameAs(original);
  }

  @Test
  void framework_passesThroughTenantException() {
    TenantUsageException original = new TenantUsageException("tenant bug", null);
    assertThatThrownBy(
            () ->
                HandleErrors.framework(
                    "op",
                    () -> {
                      throw original;
                    }))
        .isSameAs(original);
  }

  @Test
  void framework_wrapsGenericExceptionAsFrameworkException() {
    IllegalArgumentException boom = new IllegalArgumentException("boom");
    assertThatThrownBy(
            () ->
                HandleErrors.framework(
                    "myOp",
                    () -> {
                      throw boom;
                    }))
        .isInstanceOf(FrameworkException.class)
        .hasMessageContaining("myOp")
        .hasMessageContaining("boom")
        .hasCause(boom);
  }

  // ── tenant() ──────────────────────────────────────────────────────────────────

  @Test
  void tenant_returnsValueFromBlock() {
    String result = HandleErrors.tenant("resolver", () -> "hello");
    assertThat(result).isEqualTo("hello");
  }

  @Test
  void tenant_passesThroughPassthroughException() {
    FrameworkException original = new FrameworkException("framework bug", null);
    assertThatThrownBy(
            () ->
                HandleErrors.tenant(
                    "resolver",
                    () -> {
                      throw original;
                    }))
        .isSameAs(original);
  }

  @Test
  void tenant_wrapsGenericExceptionAsTenantResolverException() {
    IllegalArgumentException boom = new IllegalArgumentException("boom");
    assertThatThrownBy(
            () ->
                HandleErrors.tenant(
                    "MyResolver",
                    () -> {
                      throw boom;
                    }))
        .isInstanceOf(TenantResolverException.class)
        .hasCause(boom)
        .extracting("resolver")
        .isEqualTo("MyResolver");
  }
}
