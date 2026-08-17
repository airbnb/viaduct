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
public class Order extends NodeObjectBase implements Node, Auditable, Timestamped, SearchHit {

    public static final Type<Order> Reflection = Type.ofClass(Order.class);

    public static final class Fields implements TypeFields<Order> {
        private Fields() {}

        public static final Field<Order> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Order> id =
                                Field.of("id", Reflection);

                public static final CompositeField<Order, OrderStatus> status =
                                CompositeField.of("status", Reflection, OrderStatus.Reflection);

                public static final CompositeField<Order, Money> total =
                                CompositeField.of("total", Reflection, Money.Reflection);

                public static final Field<Order> createdAt =
                                Field.of("createdAt", Reflection);

                public static final Field<Order> updatedAt =
                                Field.of("updatedAt", Reflection);

                public static final Field<Order> auditTrail =
                                Field.of("auditTrail", Reflection);

                public static final CompositeField<Order, User> buyer =
                                CompositeField.of("buyer", Reflection, User.Reflection);

    }

    public Order(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Order(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public Order(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }

    public Order(InternalContext context, NodeReference nodeReference) {
        super(context, nodeReference);
    }

        public GlobalID<Order> getId() {
            return fetchGlobalID("id");
        }

        public OrderStatus getStatus() {
            return fetchEnum("status", OrderStatus.class);
        }

        public Money getTotal() {
            return fetchObject("total", Money::new);
        }

        public String getCreatedAt() {
            return fetchScalar("createdAt");
        }

        public String getUpdatedAt() {
            return fetchScalar("updatedAt");
        }

        public List<String> getAuditTrail() {
            return fetchScalarList("auditTrail");
        }

        public User getBuyer() {
            return fetchObject("buyer", User::new);
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

                public Builder id(GlobalID<Order> id) {
                    id = OutputBuilderTypeChecker.checkField(__context, "Order", "id", id);
                    data.put("id", id == null ? null : __context.getGlobalIDCodec().serialize(id.getType().getName(), id.getInternalID()));
        return this;
                }

                public Builder status(OrderStatus status) {
                    status = OutputBuilderTypeChecker.checkField(__context, "Order", "status", status);
                    data.put("status", status);
        return this;
                }

                public Builder total(Money total) {
                    total = OutputBuilderTypeChecker.checkField(__context, "Order", "total", total);
                    data.put("total", total);
        return this;
                }

                public Builder createdAt(String createdAt) {
                    createdAt = OutputBuilderTypeChecker.checkField(__context, "Order", "createdAt", createdAt);
                    data.put("createdAt", createdAt);
        return this;
                }

                public Builder updatedAt(String updatedAt) {
                    updatedAt = OutputBuilderTypeChecker.checkField(__context, "Order", "updatedAt", updatedAt);
                    data.put("updatedAt", updatedAt);
        return this;
                }

                public Builder auditTrail(List<String> auditTrail) {
                    auditTrail = OutputBuilderTypeChecker.checkField(__context, "Order", "auditTrail", auditTrail);
                    data.put("auditTrail", auditTrail);
        return this;
                }

                public Builder buyer(User buyer) {
                    buyer = OutputBuilderTypeChecker.checkField(__context, "Order", "buyer", buyer);
                    data.put("buyer", buyer);
        return this;
                }


        public Order build() {
            return new Order(__context, new LinkedHashMap<>(data));
        }
    }
}