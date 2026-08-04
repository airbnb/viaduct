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

public class CreateOrderInput extends InputBase {

    public static final Type<CreateOrderInput> Reflection = Type.ofClass(CreateOrderInput.class);

    public static final class Fields implements TypeFields<CreateOrderInput> {
        private Fields() {}

        public static final Field<CreateOrderInput> __typename =
                Field.of("__typename", Reflection);
                public static final Field<CreateOrderInput> buyerId =
                                Field.of("buyerId", Reflection);

                public static final CompositeField<CreateOrderInput, Color> color =
                                CompositeField.of("color", Reflection, Color.Reflection);

                public static final Field<CreateOrderInput> amounts =
                                Field.of("amounts", Reflection);

                public static final Field<CreateOrderInput> note =
                                Field.of("note", Reflection);

    }

    // Package-private: input GRTs are constructed only through the validating Builder or
    // by sibling GRTs in this package (nested-input wrapping). Tenants cannot construct
    // one directly, so a @oneOf input cannot bypass the builder's fail-fast validation.
    CreateOrderInput(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

    /**
     * Returns whether {@code field} was explicitly provided, including an explicit
     * {@code null}.
     *
     * <p>This is meaningful only for top-level fields. graphql-java applies input
     * coercion, including default values, to nested input objects, so presence cannot
     * be determined for fields nested more deeply than this input.
     */
    public boolean isPresent(Field<CreateOrderInput> field) {
        return isFieldPresent(field);
    }

        public String getBuyerId() {
            return get("buyerId");
        }

        public Color getColor() {
            return getEnum("color", Color.class);
        }

        public List<Double> getAmounts() {
            return getScalarList("amounts");
        }

        public String getNote() {
            return get("note");
        }


    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context));
    }

    public static class Builder {
        private final InternalContext __context;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context) {
            this.__context = __context;
        }

                public Builder buyerId(String buyerId) {
                    data.put("buyerId", buyerId);
        return this;
                }

                public Builder color(Color color) {
                    data.put("color", color);
        return this;
                }

                public Builder amounts(List<Double> amounts) {
                    data.put("amounts", amounts);
        return this;
                }

                public Builder note(String note) {
                    data.put("note", note);
        return this;
                }


        public CreateOrderInput build() {
            return new CreateOrderInput(__context, new LinkedHashMap<>(data), null);
        }
    }
}