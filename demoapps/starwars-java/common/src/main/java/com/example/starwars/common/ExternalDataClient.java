package com.example.starwars.common;

/** Stands in for an outbound database or HTTP dependency. */
public interface ExternalDataClient {
  String getSourceName();

  String fetchData(String key);
}
