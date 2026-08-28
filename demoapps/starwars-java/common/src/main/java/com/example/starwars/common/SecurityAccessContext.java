package com.example.starwars.common;

import java.util.Map;
import java.util.function.Supplier;

/** Per-execution security context used by the mutation examples. */
public final class SecurityAccessContext {
  private static final String ADMIN_ACCESS = "admin";
  public static final String REQUEST_CONTEXT_KEY = SecurityAccessContext.class.getName();

  private final String securityAccess;

  public SecurityAccessContext(String securityAccess) {
    this.securityAccess = securityAccess;
  }

  public static SecurityAccessContext from(Object requestContext) {
    if (requestContext instanceof Map<?, ?> values
        && values.get(REQUEST_CONTEXT_KEY) instanceof SecurityAccessContext securityContext) {
      return securityContext;
    }
    return new SecurityAccessContext(null);
  }

  public <T> T validateAccess(Supplier<T> block) {
    if (!ADMIN_ACCESS.equals(securityAccess)) {
      throw new SecurityException("Insufficient permissions!");
    }
    return block.get();
  }
}
