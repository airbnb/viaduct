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

@SuppressWarnings("MissingOverride")
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

    @SuppressWarnings("UnusedMethod")
    private OrderEdge(InternalContext context, Map<String, Object> data) {
        super(context, data, "OrderEdge");
    }

    private OrderEdge(InternalContext context, ObjectBase base, Map<String, Object> data) {
        super(context, base, data, "OrderEdge");
    }

    public OrderEdge(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public String getCursorOrThrow(String alias) {
            return fetchScalar("cursor", alias);
        }

        public String getCursorOrThrow() {
            return fetchScalar("cursor", null);
        }

        public String getCursor(String alias) {
            return nullOnDataFailure(() -> fetchScalar("cursor", alias));
        }

        public String getCursor() {
            return nullOnDataFailure(() -> fetchScalar("cursor", null));
        }

        public Order getNodeOrThrow(String alias) {
            return fetchObject("node", alias, Order.class, Order::new);
        }

        public Order getNodeOrThrow() {
            return fetchObject("node", null, Order.class, Order::new);
        }

        public Order getNode(String alias) {
            return nullOnDataFailure(() -> fetchObject("node", alias, Order.class, Order::new));
        }

        public Order getNode() {
            return nullOnDataFailure(() -> fetchObject("node", null, Order.class, Order::new));
        }


    public Builder toBuilder() {
        return new Builder(__context(), toBuilderBase());
    }

    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context), null);
    }

    public static class Builder {
        private final InternalContext __context;
        private final ObjectBase __base;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context, ObjectBase __base) {
            this.__context = __context;
            this.__base = __base;
        }

                public Builder cursor(String cursor) {
                    cursor = OutputBuilderTypeChecker.checkField(
                            __context,
                            "OrderEdge",
                            "cursor",
                            null,
                            cursor);
                    data.put("cursor", cursor);
        return this;
                }

                public Builder node(Order node) {
                    node = OutputBuilderTypeChecker.checkField(
                            __context,
                            "OrderEdge",
                            "node",
                            Order.class,
                            node);
                    data.put("node", node);
        return this;
                }


        public OrderEdge build() {
            return new OrderEdge(__context, __base, new LinkedHashMap<>(data));
        }
    }
}