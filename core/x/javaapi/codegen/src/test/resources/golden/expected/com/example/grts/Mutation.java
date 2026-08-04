package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import viaduct.java.api.types.Arguments;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Mutation extends ObjectBase implements viaduct.java.api.types.Mutation {

    public static final Type<Mutation> Reflection = Type.ofClass(Mutation.class);

    public static final class Fields implements TypeFields<Mutation> {
        private Fields() {}

        public static final Field<Mutation> __typename =
                Field.of("__typename", Reflection);
                public static final CompositeField<Mutation, Order> createOrder =
                                CompositeField.of("createOrder", Reflection, Order.Reflection);

    }

    public Mutation(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Mutation(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }
        public Order getCreateOrder() {
            return fetchObject("createOrder", Order::new);
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

                public Builder createOrder(Order createOrder) {
                    data.put("createOrder", createOrder);
        return this;
                }


        public Mutation build() {
            return new Mutation(__context, new LinkedHashMap<>(data));
        }
    }
}