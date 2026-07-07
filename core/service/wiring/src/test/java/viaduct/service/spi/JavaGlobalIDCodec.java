package viaduct.service.spi;

import kotlin.Pair;
import viaduct.service.api.spi.GlobalIDCodec;

/** Java implementation of the {@link GlobalIDCodec} SPI. */
public final class JavaGlobalIDCodec implements GlobalIDCodec {
  @Override
  public String serialize(String typeName, String localID) {
    return typeName + ":" + localID;
  }

  @Override
  // Deserialize returns kotlin.Pair — a Kotlin stdlib type leaking into the SPI. Java is forced
  // to import kotlin.Pair and `new Pair<>(...)`. Future PR will replace this with a named type.
  public Pair<String, String> deserialize(String globalID) {
    int i = globalID.indexOf(':');
    return new Pair<>(globalID.substring(0, i), globalID.substring(i + 1));
  }
}
