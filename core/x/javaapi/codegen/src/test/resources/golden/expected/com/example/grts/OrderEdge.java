package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.internal.OutputBuilderTypeChecker;
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

public class OrderEdge extends ObjectBase implements viaduct.java.api.types.Edge<Order> {

    public static final Type<OrderEdge> Reflection = Type.ofClass(OrderEdge.class);

    public static final class Fields implements TypeFields<OrderEdge> {
        private Fields() {}

        public static final Field<OrderEdge> __typename =
                Field.of("__typename", Reflection);
                public static final Field<OrderEdge> cursor =
                                Field.of("cursor", Reflection);

                public static final CompositeField<OrderEdge, Order> node =
                                CompositeField.of("node", Reflection, Order.Reflection);

    }

    public OrderEdge(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private OrderEdge(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public OrderEdge(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public String getCursor() {
            return fetchScalar("cursor");
        }

        public Order getNode() {
            return fetchObject("node", Order::new);
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

                public Builder cursor(String cursor) {
                    cursor = OutputBuilderTypeChecker.checkField(__context, "OrderEdge", "cursor", cursor);
                    data.put("cursor", cursor);
        return this;
                }

                public Builder node(Order node) {
                    node = OutputBuilderTypeChecker.checkField(__context, "OrderEdge", "node", node);
                    data.put("node", node);
        return this;
                }


        public OrderEdge build() {
            return new OrderEdge(__context, new LinkedHashMap<>(data));
        }
    }
}