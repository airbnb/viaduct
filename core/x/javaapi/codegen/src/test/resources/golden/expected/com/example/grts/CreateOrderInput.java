package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CreateOrderInput extends InputBase {

    public CreateOrderInput(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
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