package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

/** Enum to test that generated enums are proper Java enums. */
public enum StatusEnum implements GraphQLEnum {
  PENDING,
  ACTIVE,
  COMPLETED,
  CANCELLED;

  public static final Type<StatusEnum> Reflection = Type.ofClass(StatusEnum.class);
}
