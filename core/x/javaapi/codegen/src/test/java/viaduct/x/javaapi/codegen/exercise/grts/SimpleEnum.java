package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

/** A simple enum with multiple values. */
public enum SimpleEnum implements GraphQLEnum {
  VALUE_A,
  VALUE_B,
  VALUE_C;

  public static final Type<SimpleEnum> Reflection = Type.ofClass(SimpleEnum.class);
}
