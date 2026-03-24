package viaduct.java.runtime.featureapp.enum_example.grt;

/**
 * Status enum representing the possible states.
 *
 * <p>This enum is used as the return type for the currentStatus resolver. In production, this would
 * be generated from the GraphQL schema.
 */
public enum Status {
  ACTIVE,
  INACTIVE,
  PENDING
}
