package com.example.execution.javaoneproject;

import viaduct.java.grts.Query;

/**
 * Compiles only if the application plugin's generated Java GRT jar is on this project's classpath.
 */
final class GeneratedGrtConsumer {
  Query roundTrip(Query query) {
    return query;
  }
}
