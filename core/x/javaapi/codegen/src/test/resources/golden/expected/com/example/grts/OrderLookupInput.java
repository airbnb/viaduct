package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrderLookupInput extends InputBase {

    public static final Type<OrderLookupInput> Reflection = Type.ofClass(OrderLookupInput.class);

    public static final class Fields implements TypeFields<OrderLookupInput> {
        private Fields() {}

        public static final Field<OrderLookupInput> __typename =
                Field.of("__typename", Reflection);
                public static final Field<OrderLookupInput> byId =
                                Field.of("byId", Reflection);

                public static final Field<OrderLookupInput> byNote =
                                Field.of("byNote", Reflection);

    }

    // Package-private: input GRTs are constructed only through the validating Builder or
    // by sibling GRTs in this package (nested-input wrapping). Tenants cannot construct
    // one directly, so a @oneOf input cannot bypass the builder's fail-fast validation.
    OrderLookupInput(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

    /**
     * Returns whether this input contains a value for {@code field} after GraphQL
     * defaults are applied. Explicit {@code null} counts as present. An omitted field
     * with a schema default is present; an omitted field without a default is absent.
     */
    public boolean isPresent(Field<OrderLookupInput> field) {
        return isFieldPresent(field);
    }

        public String getById() {
            return get("byId");
        }

        public String getByNote() {
            return get("byNote");
        }


    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context));
    }

    public static class Builder {
        private final InternalContext __context;
        private final GraphQLInputObjectType graphQLInputObjectType;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context) {
            this.__context = __context;
            this.graphQLInputObjectType =
                    (GraphQLInputObjectType) __context.getSchema().getSchema().getType("OrderLookupInput");
        }

                public Builder byId(String byId) {
                    data.put("byId", byId);
        return this;
                }

                public Builder byNote(String byNote) {
                    data.put("byNote", byNote);
        return this;
                }


        public OrderLookupInput build() {
            InputBase.validateOneOf("OrderLookupInput", data);
            return new OrderLookupInput(__context, new LinkedHashMap<>(data), graphQLInputObjectType);
        }
    }
}