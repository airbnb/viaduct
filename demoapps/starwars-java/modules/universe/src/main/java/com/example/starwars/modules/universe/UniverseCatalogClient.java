package com.example.starwars.modules.universe;

import com.example.starwars.common.ExternalDataClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simulates a client for the universe-catalog database. */
@Singleton
@Named("universe")
public final class UniverseCatalogClient implements ExternalDataClient {
  private static final Logger LOG = LoggerFactory.getLogger(UniverseCatalogClient.class);
  private static final String SOURCE_NAME = "universe-catalog-db";

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
