package com.example.grts;

import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.GraphQLEnum;

public enum OrderStatus implements GraphQLEnum {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED;

    public static final Type<OrderStatus> Reflection = Type.ofClass(OrderStatus.class);
}