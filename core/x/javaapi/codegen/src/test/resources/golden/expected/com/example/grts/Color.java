package com.example.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

public enum Color implements GraphQLEnum {
    RED,
    GREEN,
    BLUE;

    public static final Type<Color> Reflection = Type.ofClass(Color.class);
}