package viaduct.service.spi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import viaduct.service.api.spi.InputStreamSource;

/**
 * Java implementation of the {@link InputStreamSource} SPI. Note that {@code openStream()} is
 * declared {@code throws IOException} — the Kotlin interface uses {@code @Throws(IOException)}, so
 * a Java implementer can (and must) declare the checked exception. This is the model S6 applies
 * elsewhere.
 */
public final class JavaInputStreamSource implements InputStreamSource {
  @Override
  public InputStream openStream() throws IOException {
    return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
  }
}
