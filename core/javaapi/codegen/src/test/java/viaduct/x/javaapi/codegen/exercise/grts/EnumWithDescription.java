package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

/** An enum with a description to test Javadoc generation. */
public enum EnumWithDescription implements GraphQLEnum {
  FIRST,
  SECOND;

  public static final Type<EnumWithDescription> Reflection =
      Type.ofClass(EnumWithDescription.class);
}
