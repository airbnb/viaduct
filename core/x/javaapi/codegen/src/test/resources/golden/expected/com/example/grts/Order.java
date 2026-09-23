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

    @SuppressWarnings("UnusedMethod")
    private Order(InternalContext context, Map<String, Object> data) {
        super(context, data, "Order");
    }

    private Order(InternalContext context, ObjectBase base, Map<String, Object> data) {
        super(context, base, data, "Order");
    }

    public Order(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }

    public Order(InternalContext context, NodeReference nodeReference) {
        super(context, nodeReference);
    }

        public GlobalID<Order> getIdOrThrow(String alias) {
            return fetchGlobalID("id", alias);
        }

        public GlobalID<Order> getIdOrThrow() {
            return fetchGlobalID("id", null);
        }

        public GlobalID<Order> getId(String alias) {
            return nullOnDataFailure(() -> fetchGlobalID("id", alias));
        }

        public GlobalID<Order> getId() {
            return nullOnDataFailure(() -> fetchGlobalID("id", null));
        }

        public OrderStatus getStatusOrThrow(String alias) {
            return fetchEnum("status", alias, OrderStatus.class);
        }

        public OrderStatus getStatusOrThrow() {
            return fetchEnum("status", null, OrderStatus.class);
        }

        public OrderStatus getStatus(String alias) {
            return nullOnDataFailure(() -> fetchEnum("status", alias, OrderStatus.class));
        }

        public OrderStatus getStatus() {
            return nullOnDataFailure(() -> fetchEnum("status", null, OrderStatus.class));
        }

        public Money getTotalOrThrow(String alias) {
            return fetchObject("total", alias, Money.class, Money::new);
        }

        public Money getTotalOrThrow() {
            return fetchObject("total", null, Money.class, Money::new);
        }

        public Money getTotal(String alias) {
            return nullOnDataFailure(() -> fetchObject("total", alias, Money.class, Money::new));
        }

        public Money getTotal() {
            return nullOnDataFailure(() -> fetchObject("total", null, Money.class, Money::new));
        }

        public String getCreatedAtOrThrow(String alias) {
            return fetchScalar("createdAt", alias);
        }

        public String getCreatedAtOrThrow() {
            return fetchScalar("createdAt", null);
        }

        public String getCreatedAt(String alias) {
            return nullOnDataFailure(() -> fetchScalar("createdAt", alias));
        }

        public String getCreatedAt() {
            return nullOnDataFailure(() -> fetchScalar("createdAt", null));
        }

        public String getUpdatedAtOrThrow(String alias) {
            return fetchScalar("updatedAt", alias);
        }

        public String getUpdatedAtOrThrow() {
            return fetchScalar("updatedAt", null);
        }

        public String getUpdatedAt(String alias) {
            return nullOnDataFailure(() -> fetchScalar("updatedAt", alias));
        }

        public String getUpdatedAt() {
            return nullOnDataFailure(() -> fetchScalar("updatedAt", null));
        }

        public List<String> getAuditTrailOrThrow(String alias) {
            return fetchScalarList("auditTrail", alias);
        }

        public List<String> getAuditTrailOrThrow() {
            return fetchScalarList("auditTrail", null);
        }

        public List<String> getAuditTrail(String alias) {
            return nullOnDataFailure(() -> fetchScalarList("auditTrail", alias));
        }

        public List<String> getAuditTrail() {
            return nullOnDataFailure(() -> fetchScalarList("auditTrail", null));
        }

        public User getBuyerOrThrow(String alias) {
            return fetchObject("buyer", alias, User.class, User::new);
        }

        public User getBuyerOrThrow() {
            return fetchObject("buyer", null, User.class, User::new);
        }

        public User getBuyer(String alias) {
            return nullOnDataFailure(() -> fetchObject("buyer", alias, User.class, User::new));
        }

        public User getBuyer() {
            return nullOnDataFailure(() -> fetchObject("buyer", null, User.class, User::new));
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

                public Builder id(GlobalID<Order> id) {
                    id = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "id",
                            null,
                            id);
                    data.put("id", id == null ? null : __context.getGlobalIDCodec().serialize(id.getType().getName(), id.getInternalID()));
        return this;
                }

                public Builder status(OrderStatus status) {
                    status = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "status",
                            OrderStatus.class,
                            status);
                    data.put("status", status);
        return this;
                }

                public Builder total(Money total) {
                    total = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "total",
                            Money.class,
                            total);
                    data.put("total", total);
        return this;
                }

                public Builder createdAt(String createdAt) {
                    createdAt = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "createdAt",
                            null,
                            createdAt);
                    data.put("createdAt", createdAt);
        return this;
                }

                public Builder updatedAt(String updatedAt) {
                    updatedAt = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "updatedAt",
                            null,
                            updatedAt);
                    data.put("updatedAt", updatedAt);
        return this;
                }

                public Builder auditTrail(List<String> auditTrail) {
                    auditTrail = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "auditTrail",
                            null,
                            auditTrail);
                    data.put("auditTrail", auditTrail);
        return this;
                }

                public Builder buyer(User buyer) {
                    buyer = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Order",
                            "buyer",
                            User.class,
                            buyer);
                    data.put("buyer", buyer);
        return this;
                }


        public Order build() {
            return new Order(__context, __base, new LinkedHashMap<>(data));
        }
    }
}