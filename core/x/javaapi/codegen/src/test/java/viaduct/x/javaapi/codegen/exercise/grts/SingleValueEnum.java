package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

/** Enum with a single value. */
public enum SingleValueEnum implements GraphQLEnum {
  ONLY_VALUE;

  public static final Type<SingleValueEnum> Reflection = Type.ofClass(SingleValueEnum.class);
}
