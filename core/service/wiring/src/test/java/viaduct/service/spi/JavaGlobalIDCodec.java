package viaduct.service.spi;

import viaduct.service.api.spi.DecodedGlobalID;
import viaduct.service.api.spi.GlobalIDCodec;

/** Java implementation of the {@link GlobalIDCodec} SPI. */
public final class JavaGlobalIDCodec implements GlobalIDCodec {
  @Override
  public String serialize(String typeName, String localID) {
    return typeName + ":" + localID;
  }

  @Override
  public DecodedGlobalID deserialize(String globalID) {
    int i = globalID.indexOf(':');
    return new DecodedGlobalID(globalID.substring(0, i), globalID.substring(i + 1));
  }
}
