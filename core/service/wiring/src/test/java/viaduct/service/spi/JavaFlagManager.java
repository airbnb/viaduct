package viaduct.service.spi;

import viaduct.service.api.spi.FlagManager;

/** Java implementation of the {@link FlagManager} SPI. */
public final class JavaFlagManager implements FlagManager {
  @Override
  public boolean isEnabled(FlagManager.Flag flag) {
    return false;
  }
}
