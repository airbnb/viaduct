package com.example.grts;

import viaduct.java.api.types.GraphQLUnion;

/**
 * Possible types: User, Order, Money
 */
public interface SearchHit extends GraphQLUnion {
}