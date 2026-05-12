package viaduct.publications.runtime;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/** Provides an explicit no-op backend for the relocated SLF4J API inside the runtime fat jar. */
public final class ShadedSlf4jNoOpServiceProvider implements SLF4JServiceProvider {
  private final ILoggerFactory loggerFactory = new NOPLoggerFactory();
  private final IMarkerFactory markerFactory = new BasicMarkerFactory();
  private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return markerFactory;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdcAdapter;
  }

  @Override
  public String getRequestedApiVersion() {
    return "2.0.99";
  }

  @Override
  public void initialize() {}
}
