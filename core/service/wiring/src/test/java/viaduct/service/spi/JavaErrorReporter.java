package viaduct.service.spi;

import viaduct.service.api.spi.ErrorReporter;

/** Java implementation of the {@link ErrorReporter} SPI — proves it is implementable from Java. */
public final class JavaErrorReporter implements ErrorReporter {
  @Override
  public void reportResolverError(
      Throwable exception, String errorMessage, ErrorReporter.Metadata metadata) {
    // no-op: a real reporter would forward to logging/monitoring infrastructure.
  }
}
