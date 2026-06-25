package com.example.grts;

import viaduct.java.api.types.GraphQLEnum;

public enum OrderStatus implements GraphQLEnum {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED
}