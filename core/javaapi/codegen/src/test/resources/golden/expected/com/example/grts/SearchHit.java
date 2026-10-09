package com.example.grts;

import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import viaduct.java.api.types.GraphQLUnion;

/**
 * Possible types: User, Order, Money
 */
public interface SearchHit extends GraphQLUnion {
    Type<SearchHit> Reflection = Type.ofClass(SearchHit.class);

    final class Fields implements TypeFields<SearchHit> {
        private Fields() {}

        public static final Field<SearchHit> __typename =
                Field.of("__typename", Reflection);
    }
}