package com.example.starwars.modules.filmography;

import com.example.starwars.common.ExternalDataClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simulates a client for an out-of-process film archive. */
@Singleton
@Named("filmography")
public final class FilmArchiveClient implements ExternalDataClient {
  private static final Logger LOG = LoggerFactory.getLogger(FilmArchiveClient.class);
  private static final String SOURCE_NAME = "film-archive-service";

  @Override
  public String getSourceName() {
    return SOURCE_NAME;
  }

  @Override
  public String fetchData(String key) {
    LOG.info("Calling {} for key={}", SOURCE_NAME, key);
    String record = SOURCE_NAME + ":" + key;
    LOG.info("Received response from {}: {}", SOURCE_NAME, record);
    return record;
  }
}
